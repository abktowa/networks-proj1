public class Main {

        public static void main(String[] args) {

        String opt = "SERVER";

        if (args.length > 0 && args.length < 1) { System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"]"); return; }
        if (args.length == 1) {
            opt = args[0];
        }
        
        if (opt.equals("CLIENT")) {
            
            Client client = new Client();
        
        } else if (opt.equals("SERVER")) {

            Server server = new Server();

        } else {
            
            System.out.println("java Main.java [\"CLIENT\" or \"SERVER\"]"); return;

        }

    }
}