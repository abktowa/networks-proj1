import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class Server {
 
    private DatagramSocket serverSocket = null; // Waits for incoming client requests

    private ArrayList<ClientInfo> _activeClients;
    private HashMap<ClientInfo, ArrayList<String>> _activeClientFileListings;
    private HashMap<ClientInfo, HashMap<String, byte[]>> _activeClientFileContents;

    private final ReentrantLock printLock = new ReentrantLock();

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
            if (clientPacket.getType() == Packet.typeToByte("HEARTBEAT")) {
                _handleHeartbeat(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.typeToByte("FILELIST")) {
                _handleFilelist(clientPacket, clientAddress, clientPort);
            } else if (clientPacket.getType() == Packet.typeToByte("FILECONTENT")) {
                _handleFileContents(clientPacket, clientAddress, clientPort);
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
                    _sendActiveClientsToAllActiveClients(Packet.typeToByte("RECOVERY"));
                });
                sendActiveClientsThread.start();
            }

            // Create directory if new client
            if (isNewClient) {
                _makeClientDirectory(newClient);
            }

            // Print active clients
            printActiveClients();
        }

    }
    private void _handleFilelist(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        // Update file listing
        // Client only sends file listing when a file has been created or deleted so no need to check
        // if this listing is different, it always will be different
        ClientInfo client = new ClientInfo(clientAddress, clientPort, clientPacket.getTime(), clientPacket.getNodeID());
        
        _makeClientDirectory(client);

        String filelistString = new String(clientPacket.getData(), StandardCharsets.UTF_8);
        ArrayList<String> fileList = new ArrayList<>(Arrays.asList(filelistString.split(",")));

        synchronized (_activeClientFileListings) {
            _activeClientFileListings.put(client, fileList);
        }

        _updateClientFileListing(client, fileList);

        // Print File Listings
        printAllActiveClientFileListings();

        // Send file listings to all clients
        _sendFileListingsToAllActiveClients();
    }
    private void _handleFileContents(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        byte[] recvdData = clientPacket.getData();
        if (recvdData == null || recvdData.length == 0) {
            System.err.println("Recvd empty FILECONTENT packet");
            return;
        }
        System.out.println("Recvd FILECONTENT packet, size: " + recvdData.length);

        // ChatGPT
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(clientPacket.getData());
            ObjectInputStream in = new ObjectInputStream(byteIn)) {

                Object obj = in.readObject();
                if (obj == null) { System.err.println("Deserialized obj is null"); return; }
                if (!(obj instanceof HashMap<?, ?>)) { System.err.println("Error: Expected HashMap<String, byte[]> but recieved: " + obj.getClass().getSimpleName()); return;}
                
                HashMap<?, ?> rawMap = (HashMap<?, ?>) obj;
                HashMap<String, byte[]> fileContents = new HashMap<>();
                
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        System.err.println("Null entry found in received fileContents map: Key=" + entry.getKey());
                        continue;
                    } 
                    if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof byte[])) {
                        System.err.println("Invalid entry type in fileContents: Key=" + entry.getKey() + ", Value Type=" + entry.getValue().getClass().getSimpleName());
                        continue;
                    }
                    
                    fileContents.put((String) entry.getKey(), (byte[]) entry.getValue());
                }

                ClientInfo client = new ClientInfo(clientAddress, clientPort, clientPacket.getTime(), clientPacket.getNodeID());
                _activeClientFileContents.put(client, fileContents);
                _writeFilesFromClient(client);

                _sendFileContentsToAllActiveClients();
                
            } catch (IOException | ClassNotFoundException e) { System.err.println("Error reading file contents from client: " + e.getMessage());
        }

    }

    // Handling Files
    private void _makeClientDirectory(ClientInfo client) {
        String clientDirectory = "Server" + File.separator + client.getNodeIDAsString();
        FileHelper.createDirectory(clientDirectory);
    }
    private void _deleteClientDirectory(ClientInfo client) {
        String clientDirectoryPath = "Server" + File.separator + client.getNodeIDAsString();
        FileHelper.deleteDirectory(clientDirectoryPath);
    }
    private void _updateClientFileListing(ClientInfo client, ArrayList<String> clientFileListing) {

        // Get Current Files
        Set<String> currentFiles = new HashSet<String>();
        Set<String> updatedFiles = new HashSet<String>();
        String nodeIDString = String.valueOf(client.getNodeID());
        String clientDirectoryPath = "Server" + File.separator + nodeIDString;
        File clientDirectory = new File(clientDirectoryPath);
        
        boolean clientDirectoryEmpty = false;
        if (clientDirectory.listFiles() == null) {
            System.out.println("Null listFiles _updateClientFileListing");
            clientDirectoryEmpty = true;
        } else {
            for (File file : clientDirectory.listFiles()) {
                currentFiles.add(file.getName());
            }
        }

        // Add New Files
        for (String file : clientFileListing) {
            updatedFiles.add(file);
            if (!currentFiles.contains(file)) {
                File newFile = new File(clientDirectory,file);
                try {
                    newFile.createNewFile();
                } catch (IOException e) { System.out.println("Error creating file: " + clientDirectory + File.separator + file + "Error: " + e.getMessage()); }
            }
        }

        if (clientDirectoryEmpty) { return; } // No need to delete files, directory was empty

        // Delete Deleted Files
        for (String file : currentFiles) {
            if (!updatedFiles.contains(file)) {
                File deleteFile = new File(clientDirectory,file);
                deleteFile.delete();
            }
        }

    }
    private void _writeFilesFromClient(ClientInfo client) {
        // Write file contents to server copy
        HashMap<String, byte[]> clientFiles = _activeClientFileContents.get(client);
        if (clientFiles == null) { return; }

        String clientDirectory = "Server" + File.separator + client.getNodeIDAsString();
        for (Map.Entry<String, byte[]> entry : clientFiles.entrySet()) {
            FileHelper.writeFile(clientDirectory + File.separator + entry.getKey(), entry.getValue());
        }
    }
    
    // Sending to Clients
    private void _sendFileContentsToAllActiveClients() {
        for (ClientInfo client : _activeClients) {

            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.typeToByte("FILECONTENT"));
            packet.setNodeID((short) -1);
            packet.setTime(System.currentTimeMillis());

            if (_activeClientFileContents == null || _activeClientFileContents.isEmpty()) {
                System.err.println("Error: _activeClientFileContents is empty"); return;
            }
            byte[] fileContentAsBytes = _hashMapToBytes(_activeClientFileContents);

            packet.setLength(fileContentAsBytes.length);
            packet.setData(fileContentAsBytes);

            System.out.println("Sending file contents to all active clients");
            _sendPacketToClient(packet, client);
        }
    }
    private void _sendFileListingsToAllActiveClients() {
        for (ClientInfo client : _activeClients) {
            
            Packet packet = new Packet();
            packet.setVersion((byte) 1);
            packet.setType(Packet.typeToByte("FILELIST"));
            packet.setNodeID((short) -1);
            packet.setTime(System.currentTimeMillis());

            if (_activeClientFileListings == null || _activeClientFileListings.isEmpty()) {
                System.err.println("Error: _activeClientFileListings is empty"); return;
            }
            byte[] fileListingsBytes = _hashMapToBytes(_activeClientFileListings);

            packet.setLength(fileListingsBytes.length);
            packet.setData(fileListingsBytes);

            _sendPacketToClient(packet, client);

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
                            _sendActiveClientsToAllActiveClients(Packet.typeToByte("FAILURE"));
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
            Thread clientHandlerThread = new Thread(() -> {
                _handleClient(recvPacket, serverSocket);
            });
            clientHandlerThread.start();

        }
    }
    private void _startServer(int serverPort) {

        try {
            serverSocket = new DatagramSocket(serverPort);
            System.out.println("Server started");
            
            // Monitor client disconnects/failures
            Thread monitorClientFailuresThread = new Thread(() -> {
                _monitorClientFailures();
            });
            monitorClientFailuresThread.start();

            _listen();

        } catch (IOException i) { System.out.println(i); } 
    }

    public Server(int serverPort) {

        _activeClients = new ArrayList<>();
        _activeClientFileListings = new HashMap<ClientInfo, ArrayList<String>>();
        _activeClientFileContents = new HashMap<>();
        _startServer(serverPort);
    }

    // Helper
    private byte[] _hashMapToBytes(HashMap<?,?> hashMap) {
        
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(hashMap);
            out.flush();
            out.reset();
            return byteOut.toByteArray();
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
        return new byte[0];
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
    private void printAllActiveClientFileListings() {
        synchronized (printLock) {
        System.out.println("\n========== Active Client File Listings ==========");
    
        if (_activeClientFileListings == null || _activeClientFileListings.isEmpty()) {
            System.out.println("No active clients with files.");
        } else {
            for (Map.Entry<ClientInfo, ArrayList<String>> entry : _activeClientFileListings.entrySet()) {
                ClientInfo client = entry.getKey();
                ArrayList<String> fileList = entry.getValue();
    
                System.out.println("\nClient: " + client);
                System.out.printf("   Last Heartbeat: %s%n", client.getFormattedLastHeartbeatTime());
    
                if (fileList == null || fileList.isEmpty()) {
                    System.out.println("   No files available.");
                } else {
                    System.out.println("   Files:");
                    for (int i = 0; i < fileList.size(); i++) {
                        System.out.printf("     %d. %s%n", i + 1, fileList.get(i));
                    }
                }
                System.out.println("------------------------------------");
            }
        }
    
        System.out.println("============================================\n");
    }
    }
    
}