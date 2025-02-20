import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.ArrayList;
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

    // Data from Server
    private ArrayList<ClientInfo> _allActiveClients;

    // Setup
    private boolean _establishConnection(String serverAddr, int serverPort) {

        try {
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName(serverAddr);
            this.serverPort = serverPort;
            System.out.println("Connected to Server");
        } catch (UnknownHostException u) { System.out.println(u); return false;
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
        System.out.println();
        System.out.println("All Active Clients:");
        for (ClientInfo client : _allActiveClients) {
            System.out.println(client);
        }
        System.out.println();

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
        for (ClientInfo client : _allActiveClients) {
            if (!activeClientsFromPacket.contains(client)) {
                System.out.println("Dead Client: " + client);
            }
        }

        _allActiveClients = activeClientsFromPacket; // copy to all active clients

        System.out.println("Updated active clients list after failure");

        // Print Full Client List
        System.out.println();
        System.out.println("All Active Clients:");
        for (ClientInfo client : _allActiveClients) {
            System.out.println(client);
        }
        System.out.println();

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
                                StandardWatchEventKinds.ENTRY_DELETE);
        
        while (true) {
            WatchKey key = watchService.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    _sendFileListing();
                } else if  (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    _sendFileListing();
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
            // TODO: handle empty directory, should still send empty array to Server
            for (File filename : files) {
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
    
    // Setup
    public Client(String serverAddr, int serverPort, short nodeID) {

        _nodeID = nodeID;

        // Establish connection
        boolean conn = _establishConnection(serverAddr, serverPort);
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
    
}