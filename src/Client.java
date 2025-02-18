import java.io.*;
import java.net.*;

public class Client {

    private Socket socket = null;
    private BufferedReader in = null; // Read data coming from socket
    private BufferedWriter out = null; // Send data through socket

    private boolean _establishConnection(String serverAddr, int serverPort) {

        try {
            socket = new Socket(serverAddr, serverPort);
            System.out.println("Connected");

            // Input from terminal
            in = new BufferedReader(new InputStreamReader(System.in));

            // Output to socket
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        } catch (UnknownHostException u) { System.out.println(u); return false;
        } catch (IOException i) { System.out.println(i); return false; }

        return true;
    }

    private void _closeConnection() {
        try {
            in.close();
            out.close();
            socket.close();
        } catch (IOException i) { System.out.println(i); }
    }

    public Client(String serverAddr, int serverPort) {

        // Establish connection
        boolean conn = _establishConnection(serverAddr, serverPort);
        if (!conn) { System.out.println("Connection failed"); return; }

        // Read message from input
        try {
        String m = "";
        while (true) {
            m = in.readLine();
            
            out.write(m + "\n");
            out.flush();

            if (m.equals("exit")) { break; } // Disconnect client
        }
        } catch (IOException e) { System.out.println(e);}

        // Close connection
        _closeConnection();
    }
    
}
