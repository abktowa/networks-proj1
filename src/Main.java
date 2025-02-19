import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
// to run: java Main.java ["CLIENT" or "SERVER"] [ADDRESS] [PORT]
public class Main {

    // For simulating filesystem when testing on one device
    private static void _deleteNodeDir(String dirPath) {
        File dir = new File(dirPath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete(); // Delete each file
                }
            }
            dir.delete(); // Delete the directory itself
            //System.out.println("Deleted home directory: " + dirPath);
        }
    }
    private static String _createNodeFiles(short nodeID) {

        // Create "home" directory
        String directoryName = String.valueOf(nodeID); //+ File.separator + "home";
        String currDirectory = System.getProperty("user.dir");
        String dirPath = currDirectory + File.separator + directoryName;
        File dir = new File(dirPath);
        boolean dirCreated = dir.mkdir();

        //if (dirCreated) {
        //    System.out.println("Directory created successfully at: " + dirPath);
        //} else {
        //    System.out.println("Failed to create directory. It may already exist at: " + dirPath);
        //}

        // Fill home directory with a couple files
        try {
            for (int i = 0; i < 5; i++) {
                String filename = String.valueOf(nodeID) + "-" + String.valueOf(i) + ".txt";
                filename = dirPath + File.separator + filename;
                File newFile = new File(filename);
                newFile.createNewFile();
                //if (newFile.createNewFile()) { System.out.println("Created file: " + filename); }
            }
        } catch (IOException e) { System.out.println(e); }

        return dirPath;

    }
    public static void main(String[] args) {

        String opt = "SERVER";
        String serverAddress = "localhost";
        int port = 5001;

        if (args.length > 0 && args.length < 3) { System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"] [ADDRESS] [PORT]"); return; }
        if (args.length == 3) {
            opt = args[0];
            serverAddress = args[1];
            port = Integer.parseInt(args[2]);
        }
        
        if (opt.equals("CLIENT")) {
            
            System.out.println(String.format("Starting %s on %s:%d", opt, serverAddress, port));

            // Generating random node ID just because testing on one device
            SecureRandom rand = new SecureRandom();
            int randInt = rand.nextInt(Short.MAX_VALUE + 1);
            short nodeID = (short) randInt;

            // Create "home" directory for "node" and fill with 5 text files
            String dirPath = _createNodeFiles(nodeID);

            Client client = new Client(serverAddress, port, nodeID);

            // Delete "node" "files"
            _deleteNodeDir(dirPath);
        
        } else if (opt.equals("SERVER")) {
            System.out.println(String.format("Starting %s on %s:%d", opt, serverAddress, port));
            Server server = new Server(port);
        } else {
            return;
        }

    }
}