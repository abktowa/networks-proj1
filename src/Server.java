import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

public class Server {
 
    private DatagramSocket serverSocket = null; // Waits for incoming client requests

    private ArrayList<ClientInfo> _activeClients;
    private HashMap<ClientInfo, ArrayList<String>> _activeClientFileListings;

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

            // Add to active clients & send new active Client list to all clients
            if (isNewClient) {
                _activeClients.add(newClient);
                // Send active client lists on new thread
                Thread sendActiveClientsThread = new Thread(() -> {
                    _sendActiveClientsToAllActiveClients(Packet.typeToByte("RECOVERY"));
                });
                sendActiveClientsThread.start();
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
    private void _handleFilelist(Packet clientPacket, InetAddress clientAddress, int clientPort) {

        // Update file listing
        // Client only sends file listing when a file has been created or deleted so no need to check
        // if this listing is different, it always will be different
        ClientInfo client = new ClientInfo(clientAddress, clientPort, clientPacket.getTime());
        String filelistString = new String(clientPacket.getData(), StandardCharsets.UTF_8);
        ArrayList<String> fileList = new ArrayList<>(Arrays.asList(filelistString.split(",")));

        _activeClientFileListings.put(client, fileList);

        // Print File Listings
        System.out.println("All File Listings");
        for (ClientInfo c : _activeClientFileListings.keySet()) {
            ArrayList<String> fL = _activeClientFileListings.get(c);
            System.out.println(c);
            for (String f : fL) {
                System.out.println("     " + f);
            }
        }
        System.out.println();
    }

    // Sending to Clients
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
        _activeClientFileListings = new HashMap<ClientInfo, ArrayList<String>>();
        _startServer(serverPort);
    }

}