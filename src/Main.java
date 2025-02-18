
// to run: java Main.java ["CLIENT" or "SERVER"] [ADDRESS] [PORT]
public class Main {
    public static void main(String[] args) {

        String opt = "SERVER";
        String serverAddress = "localhost";
        int port = 5001;

        if (args.length > 0 && args.length < 3) { System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"] [ADDRESS] [PORT]"); return; }
        if (args.length == 3) {
            opt = args[0];
            serverAddress = args[1];
            port = Integer.parseInt(args[2]);
        }
        
        if (opt.equals("CLIENT")) {
            System.out.println(String.format("Starting %s on %s:%d", opt, serverAddress, port));
            Client client = new Client(serverAddress, port);
        } else if (opt.equals("SERVER")) {
            System.out.println(String.format("Starting %s on %s:%d", opt, serverAddress, port));
            Server server = new Server(port);
        } else {
            return;
        }

    }
}