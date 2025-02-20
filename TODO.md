# TODO

## Design & Implement Protocol

## Client-Server Implementation

### Client Functions
- [X] Send heartbeats from Client
- [X] Send file listing of designated "home" directory on Client to Server
- [ ] Send content of all files to Server
- [X] Store all other Clients' file listings
- [ ] Store all other Clients' file contents

### Server Functions
- [X] Store active Clients
- [X] Update active Client list when one dies
- [X] Update active Client list when one comes online
- [X] Store active Clients' file listings
- [ ] Store active Clients' file contents
- [X] Send all active Clients' file listings to all active Clients
- [ ] Send all active Clients' file contents to all active Clients

## Peer to Peer Implementation
- [ ] Peer(?) class to run Client and Server on same node (?)
- [ ] Send heartbeats to each active Peer
- [ ] Send file listing to each active Peer
- [ ] Send content of all files to each active Peer
- [ ] Store all other active Peers
- [ ] Store all other Peers' file listings
- [ ] Store all other Peers' file contents
