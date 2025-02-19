import java.io.*;
import java.net.*;
import java.security.SecureRandom;;

public class Client {

    // Network
    private DatagramSocket socket = null;
    private BufferedReader in = null; // Read data coming from socket
    private BufferedWriter out = null; // Send data through socket
    private InetAddress serverAddress;
    private int serverPort;

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
    }
    private void _sendPacketToServer(Packet packet) {

        try {
        byte[] packetBytes = packet.toBytes();
        DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverAddress, serverPort);
        socket.send(sendPacket);
        } catch (IOException e) {
            System.out.println("Failed to send heartbeat" + e.getMessage());
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
            packet.setType(packet.typeToByte("HEARTBEAT"));
            packet.setNodeID((short) 0);
            packet.setTime(System.currentTimeMillis());
            packet.setLength(data.length);
            packet.setData(data);

            _sendPacketToServer(packet);

            System.out.println("Sending heartbeat");
            Thread.sleep(heartbeatInterval * 1000);

        }
    } catch (InterruptedException e) { System.out.println(e); }
    }


    // Setup
    public Client(String serverAddr, int serverPort) {

        // Establish connection
        boolean conn = _establishConnection(serverAddr, serverPort);
        if (!conn) { System.out.println("Connection failed"); return; }

        _startClient();

        // Close connection
        _closeConnection();
    }
    private void _startClient() {

        // Start Heartbeats
        SecureRandom rand = new SecureRandom();
        int heartbeatInterval = rand.nextInt(29) + 1; // Generate random number 1 to 30
        Thread heartbeatThread = new Thread(() -> {
            _sendHeartbeatEvery(heartbeatInterval);
        });
        heartbeatThread.start();

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