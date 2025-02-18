import java.io.*;
import java.net.*;

public class Server {
 
    private Socket socket = null;
    private ServerSocket serverSocket = null; // Waits for incoming client requests
    private BufferedReader in = null; // Communicates with client

    private void _waitForClient() {
        try {
            while (true) {
                System.out.println("Waiting for Client...");

                Socket clienSocket = serverSocket.accept();
                System.out.println("Client connected: " + socket.getInetAddress());

                // Handle new client in its own thread
                new ClientHandler(clienSocket).start();
            }
        } catch (IOException i) { System.out.println(i); }
    }

    private boolean _startServer(int serverPort) {

        try {
            serverSocket = new ServerSocket(serverPort);
            System.out.println("Server started");
            
            _waitForClient();
            System.out.println("Closing connection");

            //Close connection
            socket.close();
            in.close();
        } catch (IOException i) { System.out.println(i); }

        return true;
    }

    public Server(int serverPort) {

        boolean serverStarted = _startServer(serverPort);

    }

}

class ClientHandler extends Thread {
    
    private Socket socket;
    private BufferedReader in;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try {

            // Take input from client socket
            String m = "";
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            while ((m = in.readLine()) != null) { // Read input until client disconnects
                
                System.out.println("Client: " + m);
                if (m.equals("exit")) { break; } // Client disconnect
                
            }
        } catch (IOException e) { System.out.println(e);
        } finally { // close in and socket always
            try { 
                if (in != null) { in.close(); }
                if (socket != null) { socket.close(); }
            } catch (IOException e) { System.out.println(e); }
        }
    }
}