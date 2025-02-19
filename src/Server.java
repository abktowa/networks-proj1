import java.io.*;
import java.net.*;

public class Server {
 
    private DatagramSocket serverSocket = null; // Waits for incoming client requests

    private void _handleClient(DatagramPacket recvPacket, DatagramSocket serverSocket) {

        try {
            Packet clientPacket = new Packet(recvPacket);
            clientPacket.print();
        } catch (IOException e) { System.out.println(e);
        } catch (ClassNotFoundException e) { System.out.println(e); }

    }

    private void _listen() throws IOException {
        while (true) {

            byte[] recvBuffer = new byte[1024];
            DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
            serverSocket.receive(recvPacket);
            
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
            
            _listen();

        } catch (IOException i) { System.out.println(i); } 
    }

    public Server(int serverPort) {
        _startServer(serverPort);
    }

}