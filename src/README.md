# Distributed File Synchronization System

## Overview
This project is a **distributed file synchronization system** that enables clients to synchronize files with a central server. The system uses **UDP packets** to transmit heartbeats, file updates, and file transfers between clients and the server.

### Features:
- **Client-Server Architecture**: Supports multiple clients connecting to a central server.
- **File Synchronization**: Clients can upload, update, and delete files, which are synchronized across all connected clients.
- **Heartbeat Monitoring**: Server detects inactive clients and removes them automatically.
- **Efficient File Transfers**: Uses UDP-based packets for lightweight, fast communication.
- **Automatic Recovery**: Clients automatically receive an updated list of active peers.

## Project Structure
```
|-- src/
|   |-- Client.java         # Client-side logic
|   |-- Server.java         # Server-side logic
|   |-- Packet.java         # Defines packet structure & serialization
|   |-- ClientInfo.java     # Stores information about active clients
|   |-- FileHelper.java     # File management utilities
|   |-- Main.java           # Entry point to start client or server
|
|-- config.txt              # Configuration file for server IP & port
|-- README.md               # Documentation
```

## Setup Instructions
### Prerequisites
- Java 8+ installed

## Usage
### **Starting the Server**
Edit `config.txt` to set the **server IP** and **port**:
   ```
   SERVER_IP=127.0.0.1
   SERVER_PORT=50501
   ```

Run the following command to start the server:
```sh
java -cp out/ Main SERVER
```

### **Starting a Client**
Run the following command to start a client:
```sh
java -cp out/ Main CLIENT
```

### **File Synchronization**
- Any file created, modified, or deleted in the client's `Project1/` directory will be **automatically synchronized** with the server and other active clients.
- Clients send **FILEUPDATE** and **FILEDELETE** packets when changes are detected.

## Technical Details
### Packet Structure
Packets are serialized using Java's `ObjectOutputStream` and contain:
| Field  | Size  | Description |
|--------|------:|-------------|
| version  | 1 byte | Protocol version |
| type     | 1 byte | Message type (HEARTBEAT, FILETRANSFER, etc.) |
| nodeID   | 2 bytes | Unique client identifier |
| time     | 8 bytes | Timestamp |
| length   | 4 bytes | Data size |
| data     | Variable | Actual payload |

### Server Responsibilities
- Receives and processes client packets
- Maintains a list of **active clients**
- Stores **synchronized files** for all clients
- Detects **client failures** using heartbeats

### Client Responsibilities
- Watches **local directory** for file changes
- Sends **FILEUPDATE** and **FILEDELETE** packets to the server
- Downloads **new files** from the server when notified

## Error Handling
- Clients automatically **reconnect** if the server restarts.
- If a file transfer fails, the server logs the error and retries on the next sync.
- The system detects **corrupt files** by checking expected vs. received byte length.
