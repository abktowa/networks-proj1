import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class Server {
 
    private DatagramSocket serverSocket = null;

    private ArrayList<ClientInfo> _activeClients;
    private HashMap<ClientInfo, ArrayList<String>> _activeClientFileListings;
    private HashMap<ClientInfo, HashMap<String, byte[]>> _activeClientFileContents;

    private final ReentrantLock printLock = new ReentrantLock();
    private ExecutorService clientThreadPool = Executors.newFixedThreadPool(10);

    private int _serverPort;

    private File homeDir;
    private File downloadedClientFiles;

    // Handling Client Packets
    private void _handleClient(DatagramPacket recvPacket, DatagramSocket serverSocket) {

        try {

            // Get sender's IP and Port
            InetAddress clientAddress = recvPacket.getAddress();
            int clientPort = recvPacket.getPort();
            System.out.println(String.format("\nRecieved packet from %s:%d", clientAddress.getHostAddress(), clientPort));

            // Deserialize DatagramPacket into cusotm Packet
            Packet clientPacket = new Packet(recvPacket);
            System.out.println(clientPacket);

            // Handle each packet Type
            if (clientPacket.getType() == Packet.TYPE_HEARTBEAT) {
                _handleHeartbeat(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.TYPE_FILELIST) {
                //_handleFilelist(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.TYPE_FILETRANSFER) {
                _handleFileTransfer(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.TYPE_FILEUPDATE) {
                _handleFileUpdate(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.TYPE_FILEDELETE) {
                _handleFileDelete(clientPacket, clientAddress, clientPort);
            } else {
                System.out.println("Unknown Packet type");
            }

        } catch (IOException e) { System.out.println(e);
        } catch (ClassNotFoundException e) { System.out.println(e); }

    }
    
    private void _handleHeartbeat(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        // Update activeClients if needed
        short nodeID = clientPacket.getNodeID();
        ClientInfo newClient = new ClientInfo(clientAddress, clientPort, clientPacket.getTime(), nodeID);
        boolean isNewClient = true;
        synchronized (_activeClients) { // Safely modify activeClients list (ChatGPT suggested this)
            if (_activeClients.size() > 0) {
                for (ClientInfo activeClient : _activeClients) { // Check if this client is new client
                    if (newClient.equals(activeClient)) { 
                        isNewClient = false; 
                        activeClient.updateLastHeartbeat(clientPacket.getTime());
                        break;
                    }
                }
            }

            // Add to active clients & send new active Client list to all clients
            if (isNewClient) {
                _activeClients.add(newClient);
                // Send active client lists on new thread
                Thread sendActiveClientsThread = new Thread(() -> {
                    _sendActiveClientsToAllActiveClients(Packet.TYPE_RECOVERY);
                });
                sendActiveClientsThread.start();
            }

            // Create directory if new client
            if (isNewClient) {
                _makeClientDirectory(newClient);
            }

            // Send new client all existing files
            if (isNewClient) {
                _sendExistingFilesToClient(newClient);
            }

            // Print active clients
            printActiveClients();
        }

    }
    
    private void _handleFileTransfer(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        try {
        
            ByteArrayInputStream byteIn = new ByteArrayInputStream(clientPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn);

            Object[] data = (Object[]) in.readObject();
            String[] metaData = ((String) data[0]).split(":"); //filename:ClientIP
            String filename = metaData[0];
            byte[] fileContent = (byte[]) data[1];

            ClientInfo client = new ClientInfo(clientAddress, clientPort, clientPacket.getTime(), clientPacket.getNodeID());

            synchronized (_activeClientFileContents) {
                _activeClientFileContents.computeIfAbsent(client, k -> new HashMap<>()).put(filename, fileContent); // ChatGPT
            }

            //_activeClientFileContents.put(client, new HashMap<String, byte[]>() {{ put(filename, fileContent); }});
            _writeFileToServer(filename, fileContent, client);
            _sendFileTransferToAllClients(filename, fileContent, client);

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error processing FILETRANSFER: " + e.getMessage());
        }

    }
    private void _handleFileUpdate(Packet clientPacket, InetAddress clientaAddress, int clientPort) {

        String filename = new String(clientPacket.getData(), StandardCharsets.UTF_8);
        ClientInfo client = new ClientInfo(clientaAddress, clientPort, clientPacket.getTime(), clientPacket.getNodeID());

        System.out.println("New file: " + filename + " added to client: " + client);

        synchronized (_activeClientFileListings) {
            _activeClientFileListings.computeIfAbsent(client, k -> new ArrayList<>()).add(filename); // ChatGPT
        }

        _updateClientFileListing(client, filename, true);
        _sendFileUpdateToAllClients(filename, client);

    }
    private void _handleFileDelete(Packet clientPacket, InetAddress clientaAddress, int clientPort) {
        
        String filename = new String(clientPacket.getData(), StandardCharsets.UTF_8);
        ClientInfo client = new ClientInfo(clientaAddress, clientPort, clientPacket.getTime(), clientPacket.getNodeID());

        System.out.println("File deleted: " + filename + " from client: " + client);

        // Remove from active file listings
        synchronized (_activeClientFileListings) {
            ArrayList<String> fileList = _activeClientFileListings.get(client);
            if (fileList != null) {
                fileList.remove(filename);
                if (fileList.isEmpty()) {
                    _activeClientFileListings.remove(client); // Remove client entry if no files left
                }
            }
        }

        // Remove from active file contents
        synchronized (_activeClientFileContents) {
            HashMap<String, byte[]> fileContents = _activeClientFileContents.get(client);
            if (fileContents != null) {
                fileContents.remove(filename);
                if (fileContents.isEmpty()) {
                    _activeClientFileContents.remove(client); // Remove client entry if no files left
                }
            }
        }

        _updateClientFileListing(client, filename, false);
        _sendFileDeleteToAllClients(filename, client);

    }

    // Handling Files
    private void _makeClientDirectory(ClientInfo client) {
        String clientDirectory = "Server" + File.separator + client.getNodeIDAsString();
        FileHelper.createDirectory(clientDirectory);
    }
    private void _deleteClientDirectory(ClientInfo client) {
        
        String clientDirectoryPath = new File(downloadedClientFiles, client.getNodeIDAsString()).getAbsolutePath();
        FileHelper.deleteDirectory(clientDirectoryPath);
    }
    private void _updateClientFileListing(ClientInfo client, String filename, Boolean isAdded) {

        String clientDirectory = downloadedClientFiles + File.separator + client.getNodeIDAsString();
        FileHelper.createDirectory(clientDirectory);

        File targetFile = new File(clientDirectory, filename);

        if (isAdded) {
            // Add file
            try {
            if (targetFile.createNewFile()) {
                System.out.println("Created new file: " + targetFile.getAbsolutePath());
            } else {
                System.out.println("File already exists: " + targetFile.getAbsolutePath());
            }
        } catch (IOException e) { System.err.println("Error creating file: " + filename + "\n" + e.getMessage()); }
        } else {
            // Delete file
            if (targetFile.exists() && targetFile.delete()) {
                System.out.println("Deleted file: " + targetFile.getAbsolutePath());
            } else {
            System.err.println("Failed to delete file: " + targetFile.getAbsolutePath());
            }
        }
    }
    private void _writeFileToServer(String filename, byte[] fileContent, ClientInfo client) {

        String clientDirectory = downloadedClientFiles + File.separator + client.getNodeIDAsString();
        FileHelper.createDirectory(clientDirectory);

        String filePath = clientDirectory + File.separator + filename;
        FileHelper.writeFile(filePath, fileContent);

        System.out.println("Updated server storage with new file contents: " + filePath);
    
    }
    private void _deleteAllDownloadedClients() {
        if (downloadedClientFiles.exists()) {
            File[] directories = downloadedClientFiles.listFiles(File::isDirectory);
            if (directories != null) {
                for (File dir : directories) {
                    FileHelper.deleteDirectory(dir.getAbsolutePath());
                }
            }
        }
    }

    // Sending to Clients
    private void _sendFileUpdateToAllClients(String filename, ClientInfo updatedClient) {

        for (ClientInfo client : _activeClients) {
            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.TYPE_FILEUPDATE);
            packet.setNodeID((short) -1);
            packet.setTime(System.currentTimeMillis());

            try {

            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(new Object[]{filename, updatedClient});
            out.flush();
            packet.setData(byteOut.toByteArray());
            _sendPacketToClient(packet, client);

            } catch (IOException e) { System.err.println("Error writing file update to bytes");}

        }

    }
    private void _sendFileDeleteToAllClients(String filename, ClientInfo updatedClient) {

        for (ClientInfo client: _activeClients) {

            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.TYPE_FILEDELETE);
            packet.setNodeID((short) -1);
            packet.setTime(System.currentTimeMillis());

            try {

                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(byteOut);
                out.writeObject(new Object[]{filename, updatedClient});
                out.flush();
                packet.setData(byteOut.toByteArray());
                _sendPacketToClient(packet, client);
    
                } catch (IOException e) { System.err.println("Error writing file update to bytes");}

        }

    }
    private void _sendFileTransferToAllClients(String filename, byte[] fileContent, ClientInfo updatedClient) {
        
        for (ClientInfo client : _activeClients) {

            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.TYPE_FILETRANSFER);
            packet.setTime(System.currentTimeMillis());
            packet.setNodeID((short) -1);

            try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(new Object[]{filename, fileContent, updatedClient});

            packet.setData(byteOut.toByteArray());

            _sendPacketToClient(packet, client);
            } catch (IOException e) {
                System.err.println("Error sending file transfer to all clients " + e.getMessage());
            }

        }

    }
    private void _sendActiveClientsToAllActiveClients(byte recoveryOrFailureType) {
        synchronized (_activeClients) {

            // Convert activeClients to string and then bytes for transmission
            StringBuilder buildClientString = new StringBuilder();
            for (ClientInfo client : _activeClients) {
                buildClientString.append(client.toString()).append(",");
            }
            String activeClientsAsString = buildClientString.toString();
            byte[] activeClientsBytes = activeClientsAsString.getBytes();

            for (ClientInfo client : _activeClients) {
                Packet packet = new Packet();
                packet.setVersion((byte) 1);
                packet.setType(recoveryOrFailureType);
                packet.setNodeID((short) -1); // -1 for Server ID
                packet.setTime(System.currentTimeMillis());
                packet.setLength(activeClientsBytes.length);
                packet.setData(activeClientsBytes);

                _sendPacketToClient(packet, client);
            }
        }
    }
    private void _sendPacketToClient(Packet packet, ClientInfo client) {

        System.out.println("Sending packet to client: " + client);
        try {
        DatagramPacket sendPacket = new DatagramPacket(packet.toBytes(), packet.toBytes().length,
                                                        client.getIpAddress(), client.getPort());
        serverSocket.send(sendPacket);
        } catch (IOException e) { System.out.println("Error sending packet to Client: " + e); }

    }

    private void _sendExistingFilesToClient(ClientInfo newClient) {
    synchronized (_activeClientFileListings) {
        for (Map.Entry<ClientInfo, ArrayList<String>> entry : _activeClientFileListings.entrySet()) {
            ClientInfo existingClient = entry.getKey();
            ArrayList<String> fileList = entry.getValue();

            if (existingClient.equals(newClient)) {
                continue; // Skip the new client itself
            }

            for (String filename : fileList) {
                // Send FILEUPDATE first
                Packet fileUpdatePacket = new Packet();
                fileUpdatePacket.setVersion((byte) 1);
                fileUpdatePacket.setType(Packet.TYPE_FILEUPDATE);
                fileUpdatePacket.setNodeID(existingClient.getNodeID());
                fileUpdatePacket.setTime(System.currentTimeMillis());
                
                try {

                    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                    ObjectOutputStream out = new ObjectOutputStream(byteOut);
                    out.writeObject(new Object[]{filename, newClient});
                    out.flush();
                    fileUpdatePacket.setData(byteOut.toByteArray());
                    _sendPacketToClient(fileUpdatePacket, newClient);
        
                    } catch (IOException e) { System.err.println("Error writing file update to bytes");}

                // Send FILETRANSFER
                HashMap<String, byte[]> fileContents = _activeClientFileContents.get(existingClient);
                if (fileContents != null && fileContents.containsKey(filename)) {
                    byte[] fileContent = fileContents.get(filename);

                    Packet fileTransferPacket = new Packet();
                    fileTransferPacket.setVersion((byte) 1);
                    fileTransferPacket.setType(Packet.TYPE_FILETRANSFER);
                    fileTransferPacket.setNodeID(existingClient.getNodeID());
                    fileTransferPacket.setTime(System.currentTimeMillis());

                    try {
                        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                        ObjectOutputStream out = new ObjectOutputStream(byteOut);
                        out.writeObject(new Object[]{filename, fileContent, existingClient});
                        out.flush();
                        fileTransferPacket.setData(byteOut.toByteArray());

                        _sendPacketToClient(fileTransferPacket, newClient);
                    } catch (IOException e) {
                        System.err.println("Error sending FILETRANSFER: " + e.getMessage());
                    }
                }
            }
        }
    }
}


    // Server Functions
    private void _monitorClientFailures() {

        try {
        while (true) {

            long currentTime = System.currentTimeMillis();
            synchronized (_activeClients) { // ChatGPT suggested this for thread safety
                for (Iterator<ClientInfo> it = _activeClients.iterator(); it.hasNext();) {
                    ClientInfo client = it.next();
                    long lastHeartbeat = client.getLastHeartbeatTime();
                    long elapsed = currentTime - lastHeartbeat;
                    // If more than 30 seconds between now and last heartbeat, client is considered dead
                    if (elapsed > (30 * 1000)) {
                        System.out.println("Detected client failure: " + client);
                        // Remove clent from acitve clients
                        it.remove();

                        // Remove client directory
                        _deleteClientDirectory(client);

                        // Delete Client fileListing and fileContent
                        synchronized (_activeClientFileListings) {
                        _activeClientFileListings.remove(client);
                        }
                        synchronized (_activeClientFileContents) {
                        _activeClientFileContents.remove(client);
                        }

                        // Send updated client list to all clients
                        Thread sendActiveClientsToAllClientsThread = new Thread(() -> {
                            _sendActiveClientsToAllActiveClients(Packet.TYPE_FAILURE);
                        });
                        sendActiveClientsToAllClientsThread.start();
                    }
                }
            }
            Thread.sleep(1000);
        }
    } catch (InterruptedException e) { System.out.println("monitorClientFailuresThread interrupred: " + e.getMessage()); }

    }
    private void _listen() throws IOException {
        while (true) {

            byte[] recvBuffer = new byte[65507]; // Max UDP packet size
            DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
            serverSocket.receive(recvPacket);
            
            // Handle incoming client messages, each on new thread
            /*Thread clientHandlerThread = new Thread(() -> {
                _handleClient(recvPacket, serverSocket);
            });
            clientHandlerThread.start();*/
            clientThreadPool.execute(() -> _handleClient(recvPacket, serverSocket));

        }
    }
    private void _startServer(int serverPort) {

        try {
            serverSocket = new DatagramSocket(50501, InetAddress.getByName("0.0.0.0"));
            System.out.printf("Server started on port: %d",serverPort);
            
            // Monitor client disconnects/failures
            Thread monitorClientFailuresThread = new Thread(() -> {
                _monitorClientFailures();
            });
            monitorClientFailuresThread.start();

            _listen();

        } catch (IOException i) { System.out.println(i); } 
    }

    public Server() {

        _loadConfig();
        _activeClients = new ArrayList<>();
        _activeClientFileListings = new HashMap<ClientInfo, ArrayList<String>>();
        _activeClientFileContents = new HashMap<>();

        homeDir = new File(System.getProperty("user.home"), "Project1");
        if (!homeDir.exists() && homeDir.mkdirs()) {
            System.out.println("Created client home directory: " + homeDir.getAbsolutePath());
        }

        downloadedClientFiles = new File(homeDir, "DownloadedClients");
        if (!downloadedClientFiles.exists() && downloadedClientFiles.mkdirs()) {
            System.out.println("Created downloaded client files directory: " + downloadedClientFiles.getAbsolutePath());
        }

        // ChatGPT
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Server shutting down. Deleting all Client files");
            _deleteAllDownloadedClients();
        }));

        System.setOut(new ServerPrintStream(System.out));

        _startServer(_serverPort);
    }
    private void _loadConfig() {
        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    if (parts[0].equals("SERVER_IP")) {
                        String serverIP = parts[1].trim();
                        //_serverAddress = InetAddress.getByName(serverIP);
                    } else if (parts[0].equals("SERVER_PORT")) {
                        _serverPort = Integer.parseInt(parts[1].trim());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading config file: " + e.getMessage());
        }
    }
    
    // Printing
    private void printActiveClients() {
        synchronized (printLock) {
        System.out.println("\n========== Active Clients ==========");
    
        if (_activeClients == null || _activeClients.isEmpty()) {
            System.out.println("No active clients.");
        } else {
            for (int i = 0; i < _activeClients.size(); i++) {
                ClientInfo client = _activeClients.get(i);
                System.out.printf("%d. Client ID: %d%n", i + 1, client.getNodeID());
                System.out.printf("   IP Address: %s%n", client.getIpAddress().getHostAddress());
                System.out.printf("   Port: %d%n", client.getPort());
                System.out.printf("   Last Heartbeat: %s%n", client.getFormattedLastHeartbeatTime());
                System.out.println("------------------------------------");
            }
        }
    
        System.out.println("====================================\n");
        }
    }
 
    private class ServerPrintStream extends PrintStream {
        private static final String RESET = "\033[0m";  // Reset color
        private static final String PINK = "\033[95m";  // Heartbeats
        private static final String RED = "\033[91m";   // Dead clients
        private static final String GREEN = "\033[92m"; // Recovered clients
        private static final String DARK_GREEN = "\033[32m"; // File updates
        private static final String LIGHT_RED = "\033[31m";  // File deletes
        private static final String BLUE = "\033[94m";  // File transfers
    
        private boolean newLine = true; // Tracks if a new line has started
    
        public ServerPrintStream(OutputStream out) {
            super(out, true);
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
            if (message.contains("heartbeat") || message.contains("HEARTBEAT")) {
                color = PINK;
            } else if (message.contains("Detected client failure")) {
                color = RED;
            } else if (message.contains("New Client:")) {
                color = GREEN;
            } else if (message.contains("New file:") || message.contains("FILEUPDATE")) {
                color = DARK_GREEN;
            } else if (message.contains("File deleted:") || message.contains("FILEDELETE")) {
                color = LIGHT_RED;
            } else if (message.contains("FILETRANSFER")) {
                color = BLUE;
            }
    
            // **Only add "[SERVER] " if it's not already there**
            if (newLine && !message.contains("[SERVER]")) {
                message = "[SERVER] " + message;
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
    
            // **Only add "[SERVER] " if it's not already there**
            if (newLine && !message.contains("[SERVER]")) {
                message = "[SERVER] " + message;
            }
    
            super.print(message);
            newLine = message.endsWith("\n");
        }
    
        @Override
        public PrintStream printf(String format, Object... args) {
            String message = String.format(format, args);
    
            // **Only add "[SERVER] " if it's not already there**
            if (newLine && !message.contains("[SERVER]")) {
                message = "[SERVER] " + message;
            }
    
            super.print(message);
            newLine = message.endsWith("\n");
            return this;
        }
    }
    
}