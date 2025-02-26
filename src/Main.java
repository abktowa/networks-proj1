import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
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
            short nodeID;
            try {
            URL whatismyip = new URL("http://checkip.amazonaws.com");
            BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
            String ip = in.readLine();
            String[] ipParts = ip.split("\\.");
            int part3 = Integer.parseInt(ipParts[2]); // Third octet
            int part4 = Integer.parseInt(ipParts[3]); // Fourth octet
            nodeID = (short) ((part3 << 8) | part4);
            } catch (IOException e) { System.out.println("_setNodeID error"); nodeID = 1;}
            
            // Create "home" directory for "node" and fill with 5 text files
            //final String dirPath = _createNodeFiles(nodeID);
            // Make sure files are deleted even if SIGINT (ChatGPT)
            //Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //    // Delete "node" "files"
            //    _deleteNodeDir(dirPath);
            //}));

            Client client = new Client();
        
        } else if (opt.equals("SERVER")) {
            System.out.println(String.format("Starting %s on %s:%d", opt, serverAddress, port));
            Server server = new Server(port);
        } else {
            return;
        }

    }
}