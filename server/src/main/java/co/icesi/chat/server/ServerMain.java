package co.icesi.chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.io.File;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto de entrada del servidor distribuido ZeroC Ice y AudioRelay UDP.
 */
public class ServerMain {

    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) {
        String configFile = "config.server";
        File f = new File(configFile);
        if (!f.exists()) {
            f = new File("server/config.server");
            if (f.exists()) {
                configFile = "server/config.server";
            }
        }

        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        if (new File(configFile).exists()) {
            initData.properties.load(configFile);
        }

        // Configuraciones por defecto si no estan en el archivo de propiedades
        if (initData.properties.getProperty("Chat.Port").isEmpty()) {
            initData.properties.setProperty("Chat.Port", "10000");
        }

        // Slice -> Java: permite deserializar las excepciones de negocio declaradas en Chat.ice.
        if (initData.properties.getProperty("Ice.Package.Chat").isEmpty()) {
            initData.properties.setProperty("Ice.Package.Chat", "co.icesi");
        }
        if (initData.properties.getProperty("Chat.Host").isEmpty()) {
            initData.properties.setProperty("Chat.Host", "0.0.0.0");
        }
        if (initData.properties.getProperty("Chat.Udp.Port").isEmpty()) {
            initData.properties.setProperty("Chat.Udp.Port", "10005");
        }
        if (initData.properties.getProperty("Ice.ThreadPool.Server.Size").isEmpty()) {
            initData.properties.setProperty("Ice.ThreadPool.Server.Size", "4");
        }
        if (initData.properties.getProperty("Ice.ThreadPool.Server.SizeMax").isEmpty()) {
            initData.properties.setProperty("Ice.ThreadPool.Server.SizeMax", "16");
        }

        int icePort = Integer.parseInt(initData.properties.getProperty("Chat.Port"));
        String host = initData.properties.getProperty("Chat.Host");
        String advertisedHost = initData.properties.getProperty("Chat.Advertised.Host");
        int udpPort = Integer.parseInt(initData.properties.getProperty("Chat.Udp.Port"));

        AudioRelay audioRelay = new AudioRelay(udpPort);
        try {
            audioRelay.start();
        } catch (SocketException e) {
            LOGGER.log(Level.SEVERE, "No fue posible iniciar AudioRelay en puerto UDP " + udpPort, e);
            System.exit(1);
        }

        try (Communicator communicator = Util.initialize(args, initData)) {
            String endpoint = "tcp -h " + host + " -p " + icePort;
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("ChatAdapter", endpoint);

            UserRegistry userRegistry = new UserRegistry();
            RoomManager roomManager = new RoomManager();
            CallManager callManager = new CallManager();
            Broadcaster broadcaster = new Broadcaster(userRegistry);
            FileRelayService fileRelay = new FileRelayService(userRegistry, roomManager);
            ClientReaper clientReaper = new ClientReaper(userRegistry, roomManager, callManager, broadcaster, audioRelay);

            broadcaster.setClientReaper(clientReaper);
            fileRelay.setClientReaper(clientReaper);

            ChatServiceI servant = new ChatServiceI(
                    userRegistry, roomManager, callManager, broadcaster, fileRelay, audioRelay, clientReaper,
                    host, advertisedHost
            );

            adapter.add(servant, Util.stringToIdentity("ChatService"));
            adapter.activate();

            clientReaper.startHealthCheck(15);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutdown hook activado: cerrando servidor...");
                clientReaper.stop();
                audioRelay.stop();
                try {
                    communicator.destroy();
                } catch (Exception ignored) {
                }
            }));

            LOGGER.info("=================================================");
            LOGGER.info("Servidor Chat Ice 3.7 iniciado exitosamente");
            LOGGER.info("Endpoint TCP Ice : " + endpoint);
            LOGGER.info("Endpoint UDP Voz : 0.0.0.0:" + audioRelay.getBoundPort());
            LOGGER.info("Host UDP anunciado: " + (advertisedHost == null || advertisedHost.isBlank()
                    ? "auto (IP local de cada conexion Ice)"
                    : advertisedHost));
            LOGGER.info("Servant Identity : ChatService");
            LOGGER.info("=================================================");

            communicator.waitForShutdown();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Fallo fatal en ServerMain", e);
            audioRelay.stop();
            System.exit(1);
        }
    }
}
