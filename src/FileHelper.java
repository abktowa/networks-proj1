import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FileHelper {
    public static boolean createDirectory(String path) {
        File dir = new File(path);
        return dir.exists() || dir.mkdirs();
    }

    public static boolean deleteDirectory(String path) {
        File dir = new File(path);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete(); // Delete each file
                }
            }
            return dir.delete(); // Delete the directory itself
        }
        return false;
    }

    public static void writeFile(String path, byte[] content) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(content);
        } catch (IOException e) {
            System.err.println("Failed to write file: " + path);
        }
    }

    public static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Paths.get(path));
    }
}