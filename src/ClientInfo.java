import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class ClientInfo {

    private InetAddress _ipAddress;
    private int _port;
    private long _lastHeartbeatTime;

    public ClientInfo(InetAddress ipAddress, int port, long lastHeartBeatTime) {
        this._ipAddress = ipAddress;
        this._port = port;
        this._lastHeartbeatTime = lastHeartBeatTime;
    }

    // Getters & Setters (ChatGPT)
    public InetAddress getIpAddress() { return _ipAddress; }
    public int getPort() { return _port; }
    public long getLastHeartbeatTime() { return _lastHeartbeatTime; }
    public void updateLastHeartbeat(long time) { this._lastHeartbeatTime = time; }

    // Comparison
    @Override
    public boolean equals(Object obj) {
        if (obj == null) { return false; }
        if (obj.getClass() != this.getClass()) { return false; }

        ClientInfo client = (ClientInfo) obj;
        if (client.getIpAddress().equals(this.getIpAddress())
            && client.getPort() == this.getPort()) { return true; }
        return false;
    }

    // Formatting & Printing
    @Override
    public String toString() {
        return String.format("%s:%d:%d", _ipAddress.getHostAddress(), _port, _lastHeartbeatTime);
    }
    
    public String getFormattedLastHeartbeatTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(getLastHeartbeatTime()));
    }

    // Serialization/Deserialization (ChatGPT)
    public static ClientInfo fromString(String data) {
        try {
            String[] parts = data.split(":");
            if (parts.length != 3) { return null; }

            InetAddress ip = InetAddress.getByName(parts[0]);
            int port = Integer.parseInt(parts[1]);
            long lastHeartbeat = Long.parseLong(parts[2]);

            return new ClientInfo(ip, port, lastHeartbeat);
        } catch (Exception e) { System.out.println("Error deserializing ClientInfo: " + e.getMessage()); return null; }
    }

    // For use as HashMap key
    @Override
    public int hashCode() {
        return Objects.hash(_ipAddress, _port);
    }
}