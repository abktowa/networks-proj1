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
    private ArrayList<String> _fileListing;
    private HashMap<String, byte[]> _fileContents;

    // Data from Server
    private ArrayList<ClientInfo> _allActiveClients;
    private HashMap<ClientInfo, ArrayList<String>> _activeClientFileListings; // TODO: Class for Filelist
    private HashMap<ClientInfo, HashMap<String, byte[]>> _activeClientFileContent;

    private final ReentrantLock printLock = new ReentrantLock();

    // Setup
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

        _heartbeatThread.interrupt();
        _watchDirectoryThread.interrupt();
    }
    
    // Communicate with Server
    private void _sendPacketToServer(Packet packet) {

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
            if (serverPacket.getType() == Packet.typeToByte("RECOVERY")) {
                _handleRecovery(serverPacket);
            } else if (serverPacket.getType() == Packet.typeToByte("FAILURE")) {
                _handleFailure(serverPacket);
            } else if (serverPacket.getType() == Packet.typeToByte("FILELIST")) {
                _handleFilelist(serverPacket);
            } else if (serverPacket.getType() == Packet.typeToByte("FILECONTENT")) {
                _handleFilecontent(serverPacket);
            } else {
                System.out.println("Unknown Packet Type from Server");
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
        for (ClientInfo client : deadClients) {
            _activeClientFileListings.remove(client);
        }


    }
    private void _handleFilecontent(Packet serverPacket) {
        if (serverPacket.getData() == null || serverPacket.getData().length == 0) {
            System.err.println("Error: Received empty FILECONTENT packet");
            return;
        }
        
        try {
            ByteArrayInputStream byteIn = new ByteArrayInputStream(serverPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn);
            Object obj = in.readObject();

            // Read serverPacket data into _activeClientFileContent
            if (!(obj instanceof HashMap<?, ?>)) { System.err.println("Error: Expected HashMap"); }

            HashMap<?,?> rawMap = (HashMap<?,?>) obj;
            HashMap<ClientInfo,HashMap<String, byte[]>> rcvdFileContents = new HashMap<>();
            for (Map.Entry<?,?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof ClientInfo || !(entry.getValue() instanceof HashMap<?,?>))) { System.err.println("Invalid entry type"); continue; }

                ClientInfo client = (ClientInfo) entry.getKey();
                HashMap<?, ?> rawFileMap = (HashMap<?,?>) entry.getValue();
                HashMap<String, byte[]> clientFiles = new HashMap<>();

                for (Map.Entry<?,?> fileEntry : rawFileMap.entrySet()) {
                    if (!(fileEntry.getKey() instanceof String) | !(fileEntry.getValue() instanceof byte[])) { System.err.println("Invalid entry type in fileEntry"); continue; }
                    clientFiles.put((String) fileEntry.getKey(), (byte[]) fileEntry.getValue());
                }

                rcvdFileContents.put(client, clientFiles);
            }

            _activeClientFileContent = rcvdFileContents;
            // Write file content to disk: each client should be its own subdirectory containing that client's files. We should also ignore file content from this client itself.
            for (ClientInfo client : _activeClientFileContent.keySet()) {
                if (client.getNodeID() == _nodeID) {
                    continue;
                }
                _writeFilesFromClient(client, _activeClientFileContent.get(client));
            }

            printActiveClientFileContent();
        } catch (IOException | ClassNotFoundException e) {
            System.out.println(e.getMessage());
        }
    }
    private void _handleFilelist(Packet serverPacket) {
        
        if (serverPacket.getData() == null || serverPacket.getData().length == 0) {
            System.err.println("Error: Received empty FILELIST packet");
            return;
        }

        try {
        ByteArrayInputStream byteIn = new ByteArrayInputStream(serverPacket.getData());
        ObjectInputStream in = new ObjectInputStream(byteIn);
        Object obj = in.readObject();

        if (!(obj instanceof HashMap<?, ?>)) { System.err.println("Error: Expected HashMap<String, byte[]> but recieved: " + obj.getClass().getSimpleName()); return;}
        HashMap<?,?> rawMap = (HashMap<?,?>) obj;
        HashMap<ClientInfo, ArrayList<String>> fileListings = new HashMap<>();
        
        for (Map.Entry<?,?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof ClientInfo && entry.getValue() instanceof ArrayList<?>) {
                ArrayList<?> list = (ArrayList<?>) entry.getValue();
                boolean allStrings = list.stream().allMatch(element -> element instanceof String);
                if (allStrings) {
                    fileListings.put((ClientInfo) entry.getKey(), (ArrayList<String>) entry.getValue());
                }
            }
        }

        _activeClientFileListings = fileListings;

        printActiveClientFilelisting();

        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Error Reading Bytes Client::_handleFileList: " + e.getMessage());
            System.out.println(e.getStackTrace());
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
            packet.setType(Packet.typeToByte("HEARTBEAT"));
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

    // File Listing
    private void _watchDirectory() {

        // Send initial file listing
        _sendFileListing();
        // https://www.geeksforgeeks.org/watch-a-directory-for-changes-in-java/
        try {
        Path directoryPath = Paths.get(String.valueOf(_nodeID));

        WatchService watchService = FileSystems.getDefault().newWatchService();
        directoryPath.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY); // Create / Delete for Listing, Modify for Contents
        
        while (true) {
            WatchKey key = watchService.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    _sendFileListing();
                } else if  (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    _sendFileListing();
                } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    _sendFileContents();
                }
            }
            key.reset();
        }
        } catch (IOException e) { System.out.println(e); 
        } catch (InterruptedException e) { System.out.println("Ended Directory Watching"); }
    }
    private void _sendFileListing() {

        // Get file listing and put in array
        ArrayList<String> fileListing = new ArrayList<String>();
        File[] files = new File(String.valueOf(_nodeID)).listFiles();
        if (files != null) { // if dir is not empty
            for (File filename : files) {
                if (filename.getName().startsWith(".") || filename.isDirectory()) { continue; }
                fileListing.add(filename.getName());
            }
        }
        _fileListing = fileListing;

        // Construct packet with file listing
        String fileListString = String.join(",", _fileListing);

        Packet packet = new Packet();
        packet.setVersion((byte) 1);
        packet.setType(Packet.typeToByte("FILELIST"));
        packet.setNodeID(_nodeID);
        packet.setTime(System.currentTimeMillis());
        packet.setLength(_fileListing.size());
        packet.setData(fileListString.getBytes());

        _sendPacketToServer(packet);
        System.out.println("Sending file listing");
    }
    private void _sendFileContents() {
        try {
        HashMap<String, byte[]> fileContents = new HashMap<>();
        File[] files = new File(String.valueOf(_nodeID)).listFiles();
        if (files != null) {
            for (File filename : files) {
                if (filename.getName().startsWith(".")) { continue; } // ignore system files
                byte[] fileContent;
                    fileContent = Files.readAllBytes(filename.toPath());
                    fileContents.put(filename.getName(), fileContent);
            }
        }

        if (fileContents.isEmpty()) {
            System.err.println("No files to send");
            return;
        }
        _fileContents = fileContents;

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);
        out.writeObject(_fileContents);
        out.flush();
        out.reset();

        byte[] fileContentAsBytes = byteOut.toByteArray();
        System.out.println("Serialized FILECONTENT packet size: " + fileContentAsBytes.length + " bytes");

        Packet packet = new Packet();
        packet.setVersion((byte) 1);
        packet.setType(Packet.typeToByte("FILECONTENT"));
        packet.setNodeID(_nodeID);
        packet.setTime(System.currentTimeMillis());
        packet.setLength(fileContentAsBytes.length);
        packet.setData(fileContentAsBytes);

        _sendPacketToServer(packet);
        System.out.println("Sent file contents to server");

        } catch (IOException e) { System.out.println(e.getMessage()); }
    }
    
    // Filesystem
    private void _writeFilesFromClient(ClientInfo client, HashMap<String, byte[]> clientFiles) {
        
        String thisClientNodeAsString = String.valueOf(_nodeID);
        String clientDirectory =  thisClientNodeAsString + File.separator + "DownloadedFiles" + File.separator + client.getNodeIDAsString();
        FileHelper.createDirectory(clientDirectory);

        for (String filename : clientFiles.keySet()) {
            String filePath = clientDirectory + File.separator + filename;
            FileHelper.writeFile(filePath, clientFiles.get(filename));
        }
    }

    // Setup
    public Client() {

        // Load Config & Set Node ID
        _loadConfig();
        if (serverAddress == null || serverPort == 0) { System.err.println("Server IP/Port not set in config"); return; }
        _setNodeID();

        System.setOut(new ClientPrintStream(System.out, _nodeID));

        _activeClientFileListings = new HashMap<>();
        _activeClientFileContent = new HashMap<>();

        // Establish connection
        boolean conn = _establishConnection(serverAddress, serverPort);
        if (!conn) { System.out.println("Connection failed"); return; }

        _startClient();

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

        // Input from terminal
        in = new BufferedReader(new InputStreamReader(System.in));
        byte[] sendData;

        try {
        while (true) {

            // Read message from command line
            String message = in.readLine();
            sendData = message.getBytes();

            // Send message as UDP packet
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, serverPort);
            socket.send(sendPacket);

            if (message.equals("exit")) { break; } // Disconnect client
        }
        } catch (IOException e) { System.out.println(e); }

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
    private class ClientPrintStream extends PrintStream {
        private final short nodeID;
        private boolean newLine = true; // Tracks if a new line has started
    
        public ClientPrintStream(OutputStream out, short nodeID) {
            super(out, true);
            this.nodeID = nodeID;
        }
    
        @Override
        public void println() { // Handle empty println()
            super.println();
            newLine = true; // Ensure prefix prints on next line
        }
    
        @Override
        public void println(String message) {
            if (message == null || message.isEmpty()) { // Handle empty/null lines
                super.println();
                newLine = true;
                return;
            }
            if (newLine && !message.startsWith("[CLIENT")) { // Prevent duplicate prefix
                message = "[CLIENT " + nodeID + "] " + message;
            }
            super.println(message);
            newLine = true; // Ensure next line gets a prefix
        }
    
        @Override
        public void print(String message) {
            if (message == null) { // Handle null messages
                super.print("");
                return;
            }
            if (newLine && !message.startsWith("[CLIENT")) { // Add prefix only at the start of a line
                message = "[CLIENT " + nodeID + "] " + message;
            }
            super.print(message);
            newLine = message.endsWith("\n"); // Detect if the message ends a line
        }
    
        @Override
        public PrintStream printf(String format, Object... args) {
            String message = String.format(format, args);
            if (newLine && !message.startsWith("[CLIENT")) { // Prevent duplicate prefix
                message = "[CLIENT " + nodeID + "] " + message;
            }
            super.print(message);
            newLine = message.endsWith("\n"); // Detect if the message ends a line
            return this;
        }
    }
    
}