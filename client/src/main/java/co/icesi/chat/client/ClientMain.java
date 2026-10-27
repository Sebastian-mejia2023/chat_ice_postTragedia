package co.icesi.chat.client;

import co.icesi.Chat.ChatCallbackPrx;
import co.icesi.Chat.ChatServicePrx;
import co.icesi.chat.client.audio.VoiceCallManager;
import co.icesi.chat.client.file.FileReceiver;
import co.icesi.chat.client.file.FileSender;
import co.icesi.chat.client.ui.CommandLineUI;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.client.util.ErrorMessages;
import co.icesi.chat.client.util.NetworkUtil;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.io.File;
import java.util.UUID;

/**
 * Punto de entrada del cliente Ice y consola interactiva.
 */
public class ClientMain {

    public static void main(String[] args) {
        String configFile = "config.client";
        File f = new File(configFile);
        if (!f.exists()) {
            f = new File("client/config.client");
            if (f.exists()) {
                configFile = "client/config.client";
            }
        }

        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        if (new File(configFile).exists()) {
            initData.properties.load(configFile);
        }

        // Slice -> Java: sin este mapeo las excepciones de negocio llegan como UnknownUserException.
        if (initData.properties.getProperty("Ice.Package.Chat").isEmpty()) {
            initData.properties.setProperty("Ice.Package.Chat", "co.icesi");
        }

        // Host del servidor (se puede pasar por argumento: ./gradlew runClient --args="192.168.1.15")
        String serverHost = "127.0.0.1";
        if (args.length > 0 && !args[0].isEmpty()) {
            serverHost = args[0];
        } else if (!initData.properties.getProperty("Chat.Server.Host").isEmpty()) {
            serverHost = initData.properties.getProperty("Chat.Server.Host");
        }

        int serverPort = 10000;
        if (!initData.properties.getProperty("Chat.Server.Port").isEmpty()) {
            serverPort = Integer.parseInt(initData.properties.getProperty("Chat.Server.Port"));
        }

        try (Communicator communicator = Util.initialize(args, initData)) {
            String proxyStr = "ChatService:tcp -h " + serverHost + " -p " + serverPort;
            ChatServicePrx chatService = ChatServicePrx.checkedCast(communicator.stringToProxy(proxyStr));
            if (chatService == null) {
                System.err.println("No fue posible obtener el proxy remoto de ChatService en " + proxyStr);
                System.exit(1);
            }

            // Adaptador de objetos del cliente para recibir callbacks del servidor
            String localIp = NetworkUtil.getLocalLanIp(serverHost);
            ObjectAdapter clientAdapter = communicator.createObjectAdapterWithEndpoints(
                    "ClientAdapter", "tcp -h " + localIp
            );

            ConsolePrinter printer = new ConsolePrinter();
            FileReceiver fileReceiver = new FileReceiver(printer);
            FileSender fileSender = new FileSender(chatService, printer);
            VoiceCallManager voiceCallManager = new VoiceCallManager(chatService, printer, serverHost);

            ChatCallbackI callbackServant = new ChatCallbackI(printer, fileReceiver, voiceCallManager);
            ChatCallbackPrx callbackPrx = ChatCallbackPrx.uncheckedCast(
                    clientAdapter.add(callbackServant, Util.stringToIdentity(UUID.randomUUID().toString()))
            );

            // Regla crucial de Ice: activar el adaptador ANTES de pasar el proxy de callback al servidor
            clientAdapter.activate();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                voiceCallManager.cleanupCall();
                try {
                    communicator.destroy();
                } catch (Exception ignored) {
                }
            }));

            CommandLineUI ui = new CommandLineUI(chatService, callbackPrx, printer, fileSender, voiceCallManager);
            ui.start();
        } catch (Exception e) {
            System.err.println("Error fatal en ClientMain: " + ErrorMessages.describe(e));
            System.exit(1);
        }
    }
}
