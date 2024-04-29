import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;

public class CallApiConsole {
	private static long read = 0;
	private static long studentRecordsFound = 0;
	private static long studentRecordsNotFound = 0;
	private static long imagesWritten = 0;
	private static long studentsWithMissingImages = 0;
	private static long errors = 0;

	private static final List<String> VALID_APP_PROFILES = Arrays.asList("dvlp", "dvlpupg", "dvlpc", "pprd", "pprdupg",
			"pprdc", "prod");

	private static void fetchImageForStudent(Properties props, String usfId, String outputDirPath) throws IOException {
		String url = props.getProperty("student.images.url") + "?identifier=" + usfId;
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();
		con.setRequestMethod("GET");
		con.setRequestProperty("Content-Type", "application/json");
		con.setRequestProperty("Accept", "application/json");
		con.setRequestProperty("client_id", props.getProperty("client.id"));
		con.setRequestProperty("client_secret", props.getProperty("client.secret"));
		int responseCode = con.getResponseCode();
		System.out.println("Response Code :: " + responseCode);
		if (responseCode == HttpURLConnection.HTTP_OK) { // success
			BufferedReader bin = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = bin.readLine()) != null) {
				response.append(inputLine);
			}
			bin.close();

			// print result
			System.out.println("Response: " + response.toString());
			StudentImageUrl stImUrl = new ObjectMapper().readValue(response.toString(), StudentImageUrl.class);
			BufferedImage img = ImageIO.read(new URL(stImUrl.getUrl()));
			ImageIO.write(img, "jpg", new File(outputDirPath, usfId + ".jpg"));
			// Delete the default picture for this student if it exists
			new File(outputDirPath, usfId + "_no_image.jpg").delete();

			imagesWritten++;
		} else {
			System.out.println(
					"Request to Student Images API failed: " + con.getResponseCode() + ", " + con.getResponseMessage());
			// if no profile picture exists for student, use a default picture
			InputStream defImgIn = CallApiConsole.class.getResourceAsStream("images/default_picture.jpg");
			Files.copy(defImgIn, new File(outputDirPath, usfId + "_no_image.jpg").toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			defImgIn.close();
			studentsWithMissingImages++;
		}
		con.disconnect();
	}

	private static boolean studentExistsForId(PreparedStatement ps, String studentUsfId) throws Exception {
		ResultSet rs = null;
		try {
			ps.setString(1, studentUsfId);
			rs = ps.executeQuery();
			rs.next();
			long count = rs.getLong(1);
			return (count > 0);
		} catch (Exception e) {
			e.printStackTrace();
			errors++;
			throw e;
		} finally {
			if (rs != null) {
				rs.close();
			}
		}
	}

	private static void readAndProcessStudentUsfIds(String profile, String inputFilePath, String outputDirPath)
			throws Exception {
		BufferedReader reader = null;
		Connection conn = null;
		PreparedStatement ps = null;
		InputStream propIn = null;
		try {
			reader = new BufferedReader(new FileReader(inputFilePath));
			propIn = CallApiConsole.class.getResourceAsStream("config.properties");
			if (propIn == null) {
				System.out.println("Unable to find config.properties");
				return;
			}
			Properties props = new Properties();
			props.load(propIn);
			propIn.close();

			String profileLc = profile.toLowerCase();
			Class.forName(props.getProperty("jdbc.driver"));
			String jdbcUrl = props.getProperty("jdbc." + profileLc + ".url");
			String dbUsername = props.getProperty("db." + profileLc + ".username");
			String dbPassword = props.getProperty("db." + profileLc + ".password");
			conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
			String query = "select count(t1.spriden_pidm) from spriden t1 inner join sgbstdn t2"
					+ " on t1.spriden_pidm = t2.sgbstdn_pidm where t1.spriden_id = ?";
			ps = conn.prepareStatement(query);

			String currStudentId = null;
			while ((currStudentId = reader.readLine()) != null) {
				if (currStudentId.isEmpty()) {
					continue;
				}

				System.out.println("Current Student ID: " + currStudentId);
				read++;
				ps.setString(1, currStudentId);
				try {
					boolean exists = studentExistsForId(ps, currStudentId);
					if (exists) {
						studentRecordsFound++;
						fetchImageForStudent(props, currStudentId, outputDirPath);
					} else {
						System.out.println("No student record found for ID " + currStudentId);
						studentRecordsNotFound++;
					}
				} catch (Exception e2) {
					e2.printStackTrace();
					errors++;
				}

				if ((read % 100) == 0) {
					System.out.println("# of student IDs fetched so far: " + read);
					System.out.println("# of student records found so far: " + studentRecordsFound);
					System.out.println("# of student records not found so far: " + studentRecordsNotFound);
					System.out.println("# of images written so far for students: " + imagesWritten);
					System.out.println("# of students so far with missing images: " + studentsWithMissingImages);
					System.out.println("# of errors so far: " + errors);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (reader != null) {
				reader.close();
			}
			if (ps != null) {
				ps.close();
			}
			if (conn != null) {
				conn.close();
			}
			if (propIn != null) {
				propIn.close();
			}

			System.out.println("Total # of student IDs fetched: " + read);
			System.out.println("Total # of student records found: " + studentRecordsFound);
			System.out.println("Total # of student records not found: " + studentRecordsNotFound);
			System.out.println("Total # of images written for students: " + imagesWritten);
			System.out.println("Total # of students with missing images: " + studentsWithMissingImages);
			System.out.println("Total # of errors: " + errors);
		}
	}

	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("Usage: " + CallApiConsole.class.getName()
					+ " [Profile] [Input File Path Containing List of Student IDs] "
					+ "[Directory Path to Save Student Images to]]");
			System.exit(1);
		}

		if (!VALID_APP_PROFILES.contains(args[0])) {
			System.err.println("Profile must be one of the following:");
			VALID_APP_PROFILES.forEach(vp -> {
				System.err.println(vp);
			});
			System.exit(1);
		}

		if (!new File(args[1]).exists()) {
			System.err.println("Input File " + args[1] + " does not exist!");
			System.exit(1);
		}

		if (!new File(args[2]).exists()) {
			System.err.println("Output Directory " + args[2] + " does not exist!");
			System.exit(1);
		}

		try {
			readAndProcessStudentUsfIds(args[0], args[1], args[2]);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}