import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Date;

// Protocol
public class Packet implements Serializable {

    // Implements Serializable to convert to bytes

    // version  1 byte   protocol version
    // type     1 byte   message type
    // node ID  2 bytes  identify client / server
    // time     8 bytes  time sent
    // length   4 bytes  data packet size
    // data     variable

    private byte _version;
    private byte _type;
    private short _nodeID;
    private long _time;
    private int _length;
    private byte[] _data;

    public Packet() {
        this._version = (byte) 1;
        this._type = (byte) 0;
        this._nodeID = (short) -1;
        this._time = System.currentTimeMillis();
        this._length = 0;
        this._data = new byte[0]; 
    }

    public Packet(byte version, byte type, short nodeID, long time, int length, byte[] data) {
        this._version = version;
        this._type = type;
        this._nodeID = nodeID;
        this._time = time;
        this._length = length;
        this._data = data;
    }

    // Getters & Setters (Chat GPT)
    public byte getVersion() { return _version; }
    public void setVersion(byte version) { this._version = version; }

    public byte getType() { return _type; }
    public void setType(byte type) { this._type = type; }

    public short getNodeID() { return _nodeID; }
    public void setNodeID(short nodeID) { this._nodeID = nodeID; }

    public long getTime() { return _time; }
    public void setTime(long time) { this._time = time; }

    public int getLength() { return _length; }
    public void setLength(int length) { this._length = length; }

    public byte[] getData() { return _data; }
    public void setData(byte[] data) { this._data = (data != null) ? data.clone() : null; }


    // Type
    public static final byte TYPE_HEARTBEAT = 0x01;
    public static final byte TYPE_FAILURE = 0x02;
    public static final byte TYPE_RECOVERY = 0x03;
    public static final byte TYPE_FILELIST = 0x04;
    public static final byte TYPE_FILEUPDATE = 0x05;
    public static final byte TYPE_FILEDELETE = 0x06;
    public static final byte TYPE_FILETRANSFER = 0x07;

    public static String byteToType(byte type) {
        switch (type) {
            case (byte) 0 :
                return "UNKNOWN";
            case (byte) 1 :
                return "HEARTBEAT";
            case (byte) 2 :
                return "FAILURE";
            case (byte) 3 :
                return "RECOVERY";
            case (byte) 4 :
                return "FILELIST";
            case (byte) 5 :
                return "FILEUPDATE";
            case (byte) 6 :
                return "FILEDELETE";
            case (byte) 7 :
                return "FILETRANSFER";
            default :
                return String.format("Unknown Byte: %d", (int) type);
        }
    }

    // Packet (ChatGPT)
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        ObjectOutputStream oOut = new ObjectOutputStream(bOut);
        oOut.writeObject(this);
        oOut.flush();
        return bOut.toByteArray();
    }
    public Packet(DatagramPacket recvdPacket) throws IOException, ClassNotFoundException {
        
        ByteArrayInputStream bIn = new ByteArrayInputStream(recvdPacket.getData(), 0, recvdPacket.getLength());
        ObjectInputStream oIn = new ObjectInputStream(bIn);
        Packet constructedPacket = (Packet) oIn.readObject();

        this._version = constructedPacket._version;
        this._type = constructedPacket._type;
        this._nodeID = constructedPacket._nodeID;
        this._time = constructedPacket._time;
        this._length = constructedPacket._length;
        this._data = constructedPacket._data;
    } 

    private String colorForType(byte type) {
        switch (type) {
            case Packet.TYPE_HEARTBEAT :
                return PINK;
            case Packet.TYPE_FAILURE :
                return RED;
            case Packet.TYPE_FILEDELETE :
                return LIGHT_RED;
            case Packet.TYPE_FILETRANSFER :
                return BLUE;
            case Packet.TYPE_FILEUPDATE :
                return DARK_GREEN;
            case Packet.TYPE_RECOVERY :
                return GREEN;
            default :
                return RESET;

        }
    }

    // Formatting & Printing Packet
    private static final String RESET = "\033[0m";  // Reset color
    private static final String PINK = "\033[95m";  // Heartbeats
    private static final String RED = "\033[91m";   // Dead clients
    private static final String GREEN = "\033[92m"; // Recovered clients
    private static final String DARK_GREEN = "\033[32m"; // File updates
    private static final String LIGHT_RED = "\033[31m";  // File deletes
    private static final String BLUE = "\033[94m";  // File transfers
    @Override
    public String toString() {
        String out = String.format("Packet Recieved: \n" +
                                    "Version: %d\n" +
                                    "Type: %s\n" +
                                    "Node ID: %d\n" +
                                    "Time: %s\n" +
                                    "Data: %s\n", 
                                    (int) this._version, 
                                    Packet.byteToType(this._type), 
                                    (int) this._nodeID, 
                                    this.getFormattedTime(), 
                                    new String(this._data));
        String color = colorForType(this._type);

        return color + out + RESET;
    }
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(this._time));
    }
}