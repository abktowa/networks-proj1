import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Client {

    // Network
    private DatagramSocket socket = null;
    private BufferedReader in = null; // Read data coming from socket
    private BufferedWriter out = null; // Send data through socket
    private InetAddress serverAddress;
    private int serverPort;
    private short _nodeID;

    // Threads
    private Thread _heartbeatThread;
    private Thread _watchDirectoryThread;
    private Thread _serverListeningThread;

    // Client Data
    private ArrayList<String> _fileListing; // helper for add/delete from file listing

    // Data from Server
    private ArrayList<ClientInfo> _allActiveClients;
    private HashMap<ClientInfo, ArrayList<String>> _activeClientFileListings;
    private HashMap<ClientInfo, HashMap<String, byte[]>> _activeClientFileContent;

    // Filesystem
    private File homeDir;
    private File downloadedClientFiles;

    private final ReentrantLock printLock = new ReentrantLock();

    // Connection Handling
    private boolean _establishConnection(InetAddress serverAddr, int serverPort) {

        try {
            socket = new DatagramSocket();
        } catch (IOException i) { System.out.println(i); return false; }

        return true;
    }
    private void _closeConnection() {
        try {
            if (in != null) { in.close(); }
            if (out != null) { out.close(); }
            if (socket != null) { socket.close(); }
        } catch (IOException i) { System.out.println(i); }

        if (_heartbeatThread != null) { _heartbeatThread.interrupt(); }
        if (_watchDirectoryThread != null) { _watchDirectoryThread.interrupt(); }
        if (_serverListeningThread != null) { _serverListeningThread.interrupt(); }

        _deleteDownloadedClients();
    }
    
    // Communicate with Server
    private synchronized void _sendPacketToServer(Packet packet) {

        packet.setNodeID(_nodeID);

        try {
        byte[] packetBytes = packet.toBytes();
        DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverAddress, serverPort);
        socket.send(sendPacket);
        } catch (IOException e) {
            System.out.println("Failed to send heartbeat" + e.getMessage());
        }
    }
    private void _listen() {
        System.out.println("Listening for server");
        try {
            while (true) {

                byte[] recvBuffer = new byte[1024];
                DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
                socket.receive(recvPacket);

                Thread serverHandlerThread = new Thread(() -> {
                    _handleServerPacket(recvPacket);
                });
                serverHandlerThread.start();
            }
        } catch (IOException e) { System.out.println("Error recving from server: " + e.getMessage()); }
    }
    
    // Handle Server
    private void _handleServerPacket(DatagramPacket packet) {

        try {
            // Deserialize packet -> Packet
            Packet serverPacket = new Packet(packet);

            // Handle each packet type
            if (serverPacket.getType() == Packet.TYPE_RECOVERY) {
                _handleRecovery(serverPacket);
            } else if (serverPacket.getType() == Packet.TYPE_FAILURE) {
                _handleFailure(serverPacket);
            } else if (serverPacket.getType() == Packet.TYPE_FILELIST) {
                //_handleFilelist(serverPacket);
            } else if (serverPacket.getType() == Packet.TYPE_FILETRANSFER) {
                _handleFileTransfer(serverPacket);
            } else if (serverPacket.getType() == Packet.TYPE_FILEUPDATE) {
                _handleFileUpdate(serverPacket);
            } else if (serverPacket.getType() == Packet.TYPE_FILEDELETE) {
                _handleFileDelete(serverPacket);
            } else {
                System.out.println("Unknown Packet Type from Server: " + serverPacket.getType());
            }
        } catch (IOException e) { System.out.println(e);
        } catch (ClassNotFoundException e) { System.out.println(e); }
    }
    private void _handleRecovery(Packet serverPacket) {

        String activeClientListAsString = new String(serverPacket.getData(), StandardCharsets.UTF_8);
        String[] clientStrings = activeClientListAsString.split(",");

        ArrayList<ClientInfo> activeClientsFromPacket = new ArrayList<ClientInfo>();
        for (String clientStr : clientStrings) {
            ClientInfo client = ClientInfo.fromString(clientStr);
            if (client != null) { activeClientsFromPacket.add(client); }
        }

        // Log recovered Clients
        for (ClientInfo client : activeClientsFromPacket) {
            if (!_allActiveClients.contains(client)) {
                System.out.println("New Client: " + client);
            }
        }

        _allActiveClients = activeClientsFromPacket; // copy to all active clients

        System.out.println("Updated active clients list after recovery");

        // Print Full Client List
        printActiveClients();

    }
    private void _handleFailure(Packet serverPacket) {

        String activeClientListAsString = new String(serverPacket.getData(), StandardCharsets.UTF_8);
        String[] clientStrings = activeClientListAsString.split(",");

        ArrayList<ClientInfo> activeClientsFromPacket = new ArrayList<ClientInfo>();
        for (String clientStr : clientStrings) {
            ClientInfo client = ClientInfo.fromString(clientStr);
            if (client != null) { activeClientsFromPacket.add(client); }
        }

        // Log failed Clients
        ArrayList<ClientInfo> deadClients = new ArrayList<>();
        for (ClientInfo client : _allActiveClients) {
            if (!activeClientsFromPacket.contains(client)) {
                System.out.println("Dead Client: " + client);
                deadClients.add(client);
            }
        }

        _allActiveClients = activeClientsFromPacket; // copy to all active clients

        System.out.println("Updated active clients list after failure");

        // Print Full Client List
        printActiveClients();

        // Delete Dead Client from File Listings
        for (ClientInfo deadClient : deadClients) {
            _activeClientFileListings.remove(deadClient);
            _deleteClientDirectory(deadClient);
        }


    }
    private void _handleFileTransfer(Packet serverPacket) {
        
        if (serverPacket.getData() == null || serverPacket.getData().length == 0) {
            System.err.println("Error: Received empty FILETRANSFER packet");
            return;
        }
        
        try {

            // Deserialize Object[]
            ByteArrayInputStream byteIn = new ByteArrayInputStream(serverPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn);
            Object[] rcvdData = (Object[]) in.readObject();

            // Validate
            if (!(rcvdData[0] instanceof String) || !(rcvdData[1] instanceof byte[]) || !(rcvdData[2] instanceof ClientInfo)) {
                System.err.println("Error: Expected Object[] with {String, byte[], ClientInfo} but received different format");
                return;
            }

            // Extract filename, file content, client
            String filename = (String) rcvdData[0];
            byte[] fileContent = (byte[]) rcvdData[1];
            ClientInfo clientInfo = (ClientInfo) rcvdData[2];
            System.out.println("Recieved FILETRANSFER: " + filename + " from client: " + clientInfo.getNodeIDAsString());

            // Directory exists
            File clientDir = new File(downloadedClientFiles, clientInfo.getNodeIDAsString());
            if (!clientDir.exists()) { clientDir.mkdirs(); }

            // Write content
            File fileToWrite = new File(clientDir, filename);
            try (FileOutputStream fos = new FileOutputStream(fileToWrite)) {
                fos.write(fileContent);
                System.out.println("Saved FILETRANSFER: " + fileToWrite.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error writing file: " + filename);
            }

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error handling FILETRANSFER: " + e.getMessage());
        }
    }
    private void _handleFileUpdate(Packet serverPacket) {

        try {

            // Deserialize Object[]
            ByteArrayInputStream byteIn = new ByteArrayInputStream(serverPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn);
            Object[] recievedData = (Object[]) in.readObject();

            // Validate
            if (recievedData.length != 2 || !(recievedData[0] instanceof String) || !(recievedData[1] instanceof ClientInfo)) {
                System.err.println("Error: Expected Object[] with {String, ClientInfo}");
                return;
            } 

            // Extract filename and ClientInfo
            String filename = (String) recievedData[0];
            ClientInfo updatedClient = (ClientInfo) recievedData[1];
            System.out.println("Received FILEUPDATE: " + filename + " from client: " + updatedClient.getNodeIDAsString());
            
            // Check dir exists for client
            File clientDir = new File(downloadedClientFiles, updatedClient.getNodeIDAsString());
            if (!clientDir.exists()) { clientDir.mkdirs(); }

            // Create new file
            File newFile = new File(clientDir, filename);
            if (!newFile.exists()) {
                if (newFile.createNewFile()) {
                    System.out.println("Created new file: " + newFile.getAbsolutePath());
                } else {
                    System.err.println("Failed to create file: " + newFile.getAbsolutePath());
                }
            } else { System.out.println("File already exists: " + newFile.getAbsolutePath()); }
        } catch (IOException | ClassNotFoundException e) { System.err.println("Error handling FILEUPDATE: " + e.getMessage()); }

    }
    private void _handleFileDelete(Packet serverPacket) {
        
        try {

            // Deserialize Object[]
            ByteArrayInputStream byteIn = new ByteArrayInputStream(serverPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn);
            Object[] rcvdData = (Object[]) in.readObject();

            // Validate
            if (rcvdData.length != 2 || !(rcvdData[0] instanceof String) || !(rcvdData[1] instanceof ClientInfo)) {
                System.err.println("Error: Expected Object[] with {String, ClientInfo} but received different format");
                return;
            }

            // Extract filename and client it belongs to
            String filename = (String) rcvdData[0];
            ClientInfo updatedClient = (ClientInfo) rcvdData[1];
            System.out.println("Receieved FILEDELETE: " + filename + " from client " + updatedClient.getNodeIDAsString());

            File clientDir = new File(downloadedClientFiles, updatedClient.getNodeIDAsString());
            File fileToDelete = new File(clientDir, filename);
            if (fileToDelete.exists() && fileToDelete.delete()) {
                System.out.println("Deleted file: " + fileToDelete.getAbsolutePath());
            } else {
            System.err.println("Failed to delete file: " + fileToDelete.getAbsolutePath());
            }

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error handling FILEDELETE: " + e.getMessage());
        }
    }
    
    // Heartbeat
    private void _sendHeartbeatEvery(int heartbeatInterval) {
        
        try {
        while (true) {

            String dataString = "IM ALIVE";
            byte[] data = dataString.getBytes();

            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.TYPE_HEARTBEAT);
            packet.setNodeID(_nodeID);
            packet.setTime(System.currentTimeMillis());
            packet.setLength(data.length);
            packet.setData(data);

            _sendPacketToServer(packet);

            System.out.println("Sending heartbeat");
            Thread.sleep(heartbeatInterval * 1000);

        }
    } catch (InterruptedException e) { System.out.println("Heartbeats Ended"); }
    }

    // File Handling
    private void _watchDirectory() {

        // https://www.geeksforgeeks.org/watch-a-directory-for-changes-in-java/
        try {
        Path directoryPath = Paths.get(homeDir.getAbsolutePath());

        WatchService watchService = FileSystems.getDefault().newWatchService();
        directoryPath.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY); // Create / Delete for Listing, Modify for Contents
        
        while (true) {
            WatchKey key = watchService.take();
            for (WatchEvent<?> event : key.pollEvents()) {

                String filename = event.context().toString();

                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    _sendFileUpdate(filename);
                    _sendFileContent(filename);
                } else if  (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    _sendFileDelete(filename);
                } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    _sendFileContent(filename);
                }
            }
            key.reset();
        }
        } catch (IOException e) { System.out.println(e); 
        } catch (InterruptedException e) { System.out.println("Ended Directory Watching"); }
    }
    private byte[] _getFileContentAsBytes(File file) throws IOException {
        
        byte[] fileContent = Files.readAllBytes(file.toPath());
        String payload = file.getName() + ":" + serverAddress.getHostAddress();

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);
        out.writeObject(new Object[]{payload, fileContent});
        out.flush();

        return byteOut.toByteArray();
    
    }
    private void _deleteDownloadedClients() {
        if (downloadedClientFiles.exists()) {
            File[] clientDirectories = downloadedClientFiles.listFiles(File::isDirectory);
            if (clientDirectories != null) {
                for (File clientDirectory : clientDirectories) {
                    FileHelper.deleteDirectory(clientDirectory.getAbsolutePath());
                }
            }
        }
    }
    private void _deleteClientDirectory(ClientInfo deadClient) {

        File deadClientDirectory = new File(downloadedClientFiles, deadClient.getNodeIDAsString());
        if (deadClientDirectory != null) {
            FileHelper.deleteDirectory(deadClientDirectory.getAbsolutePath());
        }

    }

    // Sending File Updates To Server
    private void _sendFileUpdate(String filename) {
        
        Packet packet = new Packet();
        packet.setVersion((byte) 1);
        packet.setType(Packet.TYPE_FILEUPDATE);
        packet.setTime(System.currentTimeMillis());
        packet.setData(filename.getBytes(StandardCharsets.UTF_8));

        _sendPacketToServer(packet);

        System.out.println("Sent FILEUPDATE for: " + filename);
    }
    private void _sendFileDelete(String filename) {

        Packet packet = new Packet();
        packet.setVersion((byte) 1);
        packet.setType(Packet.TYPE_FILEDELETE);
        packet.setNodeID(_nodeID);
        packet.setTime(System.currentTimeMillis());
        packet.setData(filename.getBytes(StandardCharsets.UTF_8));

        _sendPacketToServer(packet);

        System.out.println("Sent FILEDELETE for: " + filename);
    }
    private void _sendFileContent(String filename) {

        try {
            File file = new File(homeDir, filename);
            if (!file.exists()) { System.err.println("File not found: " + filename); }
        
            byte[] fileContent = _getFileContentAsBytes(file);

            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.TYPE_FILETRANSFER);
            packet.setNodeID(_nodeID);
            packet.setTime(System.currentTimeMillis());
            packet.setData(fileContent);

            _sendPacketToServer(packet);
            System.out.println("Sent FILETRANSFER for: " + filename);
        } catch (IOException e) {
            System.out.println("Error sending FILETRANSFER: " + e.getMessage());
        }

    }
    
    private void _sendAllExisitingFiles() {
        if (homeDir == null || !homeDir.exists()) {
            System.err.println("Home directory does not exist");
            return;
        }

        File[] files = homeDir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("No existing files to send");
            return;
        } 

        System.out.println("Sending all files to server");
        for (File file : files) {
            if (file.isDirectory()) continue;

            String filename = file.getName();
            _sendFileUpdate(filename);
            _sendFileContent(filename);
        }
    }

    // Setup
    public Client() {

        // Load Config & Set Node ID
        _loadConfig();
        if (serverAddress == null || serverPort == 0) { System.err.println("Server IP/Port not set in config"); return; }
        _setNodeID();

        homeDir = new File(System.getProperty("user.home"), "Project1");
        if (!homeDir.exists() && homeDir.mkdirs()) {
            System.out.println("Created client home directory: " + homeDir.getAbsolutePath());
        }
    
        downloadedClientFiles = new File(homeDir, "DownloadedClients");
        if (!downloadedClientFiles.exists() && downloadedClientFiles.mkdirs()) {
            System.out.println("Created downloaded client files directory: " + downloadedClientFiles.getAbsolutePath());
        }

        System.setOut(new ClientPrintStream(System.out, _nodeID));

        _activeClientFileListings = new HashMap<>();
        _activeClientFileContent = new HashMap<>();

        // Establish connection
        boolean conn = _establishConnection(serverAddress, serverPort);
        if (!conn) { System.out.println("Connection failed"); return; }

        // ChatGPT
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Client shutting down. Deleting all DownloadedClients");
            _deleteDownloadedClients();
        }));

        _startClient();

        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                System.out.println("Client shut down");
                break;
            }
        }

        // Close connection
        _closeConnection();
    }
    private void _startClient() {

        _allActiveClients = new ArrayList<ClientInfo>();

        // Start Heartbeats
        SecureRandom rand = new SecureRandom();
        int heartbeatInterval = rand.nextInt(29) + 1; // Generate random number 1 to 30
        _heartbeatThread = new Thread(() -> {
            _sendHeartbeatEvery(heartbeatInterval);
        });
        _heartbeatThread.start();

        // Send all files to server
        _sendAllExisitingFiles();

        // Start File Listing Sending
        _watchDirectoryThread = new Thread(() -> {
            _watchDirectory();
        });
        _watchDirectoryThread.start();

        // Start Listening from Server
        _serverListeningThread = new Thread(() -> {
            _listen();
        });
        _serverListeningThread.start();

    }
    private void _loadConfig() {
        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    if (parts[0].equals("SERVER_IP")) {
                        String serverIP = parts[1].trim();
                        serverAddress = InetAddress.getByName(serverIP);
                    } else if (parts[0].equals("SERVER_PORT")) {
                        serverPort = Integer.parseInt(parts[1].trim());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading config file: " + e.getMessage());
        }
    }
    private void _setNodeID() {
        
        // ChatGPT
        try {
        URL whatismyip = new URL("http://checkip.amazonaws.com");
        BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
        String ip = in.readLine();
        String[] ipParts = ip.split("\\.");
        int part3 = Integer.parseInt(ipParts[2]); // Third octet
        int part4 = Integer.parseInt(ipParts[3]); // Fourth octet
        _nodeID = (short) ((part3 << 8) | part4);
        } catch (IOException e) { System.out.println("_setNodeID error"); _nodeID = 1;}
    }
    
    // Printing
    public void printActiveClientFilelisting() {
        synchronized (printLock) {
        System.out.println("========== Active Client File Listings ==========");
    
        if (_activeClientFileListings == null || _activeClientFileListings.isEmpty()) {
            System.out.println("No active clients with files.");
        } else {
            for (Map.Entry<ClientInfo, ArrayList<String>> entry : _activeClientFileListings.entrySet()) {
                ClientInfo client = entry.getKey();
                ArrayList<String> fileList = entry.getValue();

                System.out.println("Client: " + client);
                if (fileList.isEmpty()) {
                    System.out.println("   No files available.");
                } else {
                    for (int i = 0; i < fileList.size(); i++) {
                        System.out.printf("   %d. %s%n", i + 1, fileList.get(i));
                    }
                }
            }
        }
        System.out.println("==================================================\n");
        }
    }
    public void printActiveClients() {
        synchronized (printLock) {
        System.out.println("========== Active Clients ==========");
    
        if (_allActiveClients == null || _allActiveClients.isEmpty()) {
            System.out.println("No active clients.");
        } else {
            for (int i = 0; i < _allActiveClients.size(); i++) {
                ClientInfo client = _allActiveClients.get(i);
                System.out.printf("%d. Client ID: %d%n", i + 1, client.getNodeID());
                System.out.printf("   IP Address: %s%n", client.getIpAddress().getHostAddress());
                System.out.printf("   Port: %d%n", client.getPort());
                System.out.printf("   Last Heartbeat: %s%n", client.getFormattedLastHeartbeatTime());
                System.out.println("------------------------------------");
            }
        }
    
        System.out.println("====================================");
        }
    }
    public void printActiveClientFileContent() {
        synchronized (printLock) {
            System.out.println("========== Active Client File Contents ==========");
    
            if (_activeClientFileContent == null || _activeClientFileContent.isEmpty()) {
                System.out.println("No active clients with file contents.");
            } else {
                for (Map.Entry<ClientInfo, HashMap<String, byte[]>> entry : _activeClientFileContent.entrySet()) {
                    ClientInfo client = entry.getKey();
                    HashMap<String, byte[]> fileContents = entry.getValue();
    
                    System.out.println("Client: " + client);
                    System.out.printf("   Last Heartbeat: %s%n", client.getFormattedLastHeartbeatTime());
    
                    if (fileContents == null || fileContents.isEmpty()) {
                        System.out.println("   No files available.");
                    } else {
                        System.out.println("   Files:");
                        for (Map.Entry<String, byte[]> fileEntry : fileContents.entrySet()) {
                            String filename = fileEntry.getKey();
                            int fileSize = fileEntry.getValue().length; // File size in bytes
                            System.out.printf("     - %s (%d bytes)%n", filename, fileSize);
                        }
                    }
                    System.out.println("------------------------------------");
                }
            }
    
            System.out.println("=================================================");
        }
    }

    // ChatGPT
    // Format Client printing
    private class ClientPrintStream extends PrintStream {
        private static final String RESET = "\033[0m";  // Reset color
        private static final String PINK = "\033[95m";  // Heartbeats
        private static final String RED = "\033[91m";   // Dead clients
        private static final String GREEN = "\033[92m"; // Recovered clients
        private static final String DARK_GREEN = "\033[32m"; // File updates
        private static final String LIGHT_RED = "\033[31m";  // File deletes
        private static final String BLUE = "\033[94m";  // File transfers
    
        private final short nodeID;
        private boolean newLine = true; // Tracks if a new line has started
    
        public ClientPrintStream(OutputStream out, short nodeID) {
            super(out, true);
            this.nodeID = nodeID;
        }
    
        @Override
        public void println() {
            super.println();
            newLine = true;
        }
    
        @Override
        public void println(String message) {
            if (message == null || message.isEmpty()) {
                super.println();
                newLine = true;
                return;
            }
    
            // Apply color coding based on message content
            String color = RESET;
            if (message.contains("Sending heartbeat")) {
                color = PINK;
            } else if (message.contains("Dead Client:")) {
                color = RED;
            } else if (message.contains("New Client:")) {
                color = GREEN;
            } else if (message.contains("Received FILEUPDATE")) {
                color = DARK_GREEN;
            } else if (message.contains("Received FILEDELETE")) {
                color = LIGHT_RED;
            } else if (message.contains("Received FILETRANSFER")) {
                color = BLUE;
            }
    
            // Ensure every message starts with [CLIENT nodeID]
            if (newLine && !message.startsWith("[CLIENT")) {
                message = "[CLIENT " + nodeID + "] " + message;
            }
    
            // Apply color and print message
            super.println(color + message + RESET);
            newLine = true;
        }
    
        @Override
        public void print(String message) {
            if (message == null) {
                super.print("");
                return;
            }
    
            // Ensure every message starts with [CLIENT nodeID]
            if (newLine && !message.startsWith("[CLIENT")) {
                message = "[CLIENT " + nodeID + "] " + message;
            }
    
            super.print(message);
            newLine = message.endsWith("\n");
        }
    
        @Override
        public PrintStream printf(String format, Object... args) {
            String message = String.format(format, args);
    
            // Ensure every message starts with [CLIENT nodeID]
            if (newLine && !message.startsWith("[CLIENT")) {
                message = "[CLIENT " + nodeID + "] " + message;
            }
    
            super.print(message);
            newLine = message.endsWith("\n");
            return this;
        }
    }
    
}