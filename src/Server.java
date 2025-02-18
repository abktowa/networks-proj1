import java.io.*;
import java.net.*;

public class Server {
 
    private DatagramSocket serverSocket = null; // Waits for incoming client requests

    private boolean _startServer(int serverPort) {

        try {
            serverSocket = new DatagramSocket(serverPort);
            System.out.println("Server started");
            
            while (true) {

                byte[] recvBuffer = new byte[1024];
                DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);

                serverSocket.receive(recvPacket);
                new ClientHandler(recvPacket, serverSocket).start(); // Handle new message on its own thread
            }
            
        } catch (IOException i) { System.out.println(i); }

        return true;
    }

    public Server(int serverPort) {

        boolean serverStarted = _startServer(serverPort);

    }

}

class ClientHandler extends Thread {
    
    private DatagramPacket recvPacket;
    private DatagramSocket serverSocket; // for Server response, didnt implement yet

    public ClientHandler(DatagramPacket recvPacket, DatagramSocket serverSocket) {
        this.recvPacket = recvPacket;
        this.serverSocket = serverSocket;
    }

    public void run() {

        String message = new String(recvPacket.getData(), 0, recvPacket.getLength());
        System.out.println("Recieved from client: " + message);

        if (message.equals("exit")) { System.out.println("Client disconnect"); return; }
    }
}