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

public class Packet implements Serializable {

    // Implements Serializable to conver to bytes

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
    public byte typeToByte(String type) {
        switch (type) {
            case "HEARTBEAT":
                return (byte) 1;
            case "FAILURE":
                return (byte) 2;
            case "RECOVERY":
                return (byte) 3;
            case "FILELIST":
                return (byte) 4;
            default:
                return (byte) 0;
        }
    }
    public String byteToType(byte type) {
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

    // Formatting & Printing Packet
    @Override
    public String toString() {
        String out = String.format("Packet Recieved: \n" +
                                    "Version: %d\n" +
                                    "Type: %s\n" +
                                    "Node ID: %d\n" +
                                    "Time: %s\n" +
                                    "Data: %s\n", 
                                    (int) this._version, 
                                    this.byteToType(this._type), 
                                    (int) this._nodeID, 
                                    this.getFormattedTime(), 
                                    new String(this._data));
        return out;
    }
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(this._time));
    }
}