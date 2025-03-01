public class Main {

    // For simulating filesystem when testing on one device
    /*private static void _deleteNodeDir(String dirPath) {
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
        */
        /*
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
        */
    public static void main(String[] args) {

        String opt = "SERVER";

        if (args.length > 0 && args.length < 1) { System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"]"); return; }
        if (args.length == 1) {
            opt = args[0];
        }
        
        if (opt.equals("CLIENT")) {
            
            // Create "home" directory for "node" and fill with 5 text files
            /*final String dirPath = _createNodeFiles(nodeID);
            // Make sure files are deleted even if SIGINT (ChatGPT)
            //Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //    // Delete "node" "files"
            //    _deleteNodeDir(dirPath);
            //}));*/

            Client client = new Client();
        
        } else if (opt.equals("SERVER")) {

            Server server = new Server();

        } else {
            
            System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"]"); return;

        }

    }
}