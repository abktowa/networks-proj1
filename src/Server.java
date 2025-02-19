import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;

public class Server {
 
    private DatagramSocket serverSocket = null; // Waits for incoming client requests

    private ArrayList<ClientInfo> _activeClients;

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
            if (clientPacket.getType() == clientPacket.typeToByte("HEARTBEAT")) {
                _handleHeartbeat(clientPacket, clientAddress, clientPort);
            } else {

            }

        } catch (IOException e) { System.out.println(e);
        } catch (ClassNotFoundException e) { System.out.println(e); }

    }

    private void _handleHeartbeat(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        // Update activeClients if needed
        ClientInfo newClient = new ClientInfo(clientAddress, clientPort, clientPacket.getTime());
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

            // Add to active clients
            if (isNewClient) {
                _activeClients.add(newClient);
            }

            // Print active clients
            if (_activeClients.size() > 0) {
                System.out.println("\nActive Clients");
                for (ClientInfo activeClient : _activeClients) {
                    System.out.println(activeClient);
                }
                System.out.println();
            }
        }

    }

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
                    }
                }
            }
            Thread.sleep(1000);
        }
    } catch (InterruptedException e) { System.out.println("monitorClientFailuresThread interrupred: " + e.getMessage()); }

    }

    private void _listen() throws IOException {
        while (true) {

            byte[] recvBuffer = new byte[1024];
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
        _startServer(serverPort);
    }

}