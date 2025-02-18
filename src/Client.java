import java.io.*;
import java.net.*;

public class Client {

    private DatagramSocket socket = null;
    private BufferedReader in = null; // Read data coming from socket
    private BufferedWriter out = null; // Send data through socket
    private InetAddress serverAddress;

    private boolean _establishConnection(String serverAddr, int serverPort) {

        try {
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName(serverAddr);
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

    public Client(String serverAddr, int serverPort) {

        // Establish connection
        boolean conn = _establishConnection(serverAddr, serverPort);
        if (!conn) { System.out.println("Connection failed"); return; }

        // Read message from input
        try {
            // Input from terminal
            in = new BufferedReader(new InputStreamReader(System.in));
            byte[] sendData;

            while (true) {

                // Read message from command line
                String message = in.readLine();
                sendData = message.getBytes();

                // Send message as UDP packet
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, serverPort);
                socket.send(sendPacket);

                if (message.equals("exit")) { break; } // Disconnect client
            }
        } catch (IOException e) { System.out.println(e);}

        // Close connection
        _closeConnection();
    }
    
}
