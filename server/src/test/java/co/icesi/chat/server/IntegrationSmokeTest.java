package co.icesi.chat.server;

import co.icesi.Chat.ChatCallback;
import co.icesi.Chat.ChatCallbackPrx;
import co.icesi.Chat.ChatServicePrx;
import co.icesi.Chat.FileChunk;
import co.icesi.Chat.PrivateMessage;
import co.icesi.Chat.RoomMessage;
import co.icesi.Chat.UdpEndpoint;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationSmokeTest {

    private Communicator serverCommunicator;
    private Communicator clientCommunicator;
    private AudioRelay audioRelay;
    private int serverPort;

    @BeforeEach
    public void setUp() throws Exception {
        // AudioRelay efimero
        audioRelay = new AudioRelay(0);
        audioRelay.start();

        InitializationData initServer = new InitializationData();
        initServer.properties = Util.createProperties();
        initServer.properties.setProperty("Ice.MessageSizeMax", "1024");
        initServer.properties.setProperty("Ice.ThreadPool.Server.Size", "2");

        serverCommunicator = Util.initialize(new String[0], initServer);
        ObjectAdapter adapter = serverCommunicator.createObjectAdapterWithEndpoints("TestAdapter", "tcp -h 127.0.0.1 -p 0");

        UserRegistry userRegistry = new UserRegistry();
        RoomManager roomManager = new RoomManager();
        CallManager callManager = new CallManager();
        Broadcaster broadcaster = new Broadcaster(userRegistry);
        FileRelayService fileRelay = new FileRelayService(userRegistry, roomManager);
        ClientReaper reaper = new ClientReaper(userRegistry, roomManager, callManager, broadcaster, audioRelay);

        broadcaster.setClientReaper(reaper);
        fileRelay.setClientReaper(reaper);

        ChatServiceI servant = new ChatServiceI(
                userRegistry, roomManager, callManager, broadcaster, fileRelay, audioRelay, reaper, "127.0.0.1"
        );
        adapter.add(servant, Util.stringToIdentity("ChatService"));
        adapter.activate();

        serverPort = ((com.zeroc.Ice.IPEndpointInfo) adapter.getEndpoints()[0].getInfo()).port;

        InitializationData initClient = new InitializationData();
        initClient.properties = Util.createProperties();
        initClient.properties.setProperty("Ice.MessageSizeMax", "1024");
        clientCommunicator = Util.initialize(new String[0], initClient);
    }

    @AfterEach
    public void tearDown() {
        if (clientCommunicator != null) {
            try {
                clientCommunicator.destroy();
            } catch (Exception ignored) {
            }
        }
        if (serverCommunicator != null) {
            try {
                serverCommunicator.destroy();
            } catch (Exception ignored) {
            }
        }
        if (audioRelay != null) {
            audioRelay.stop();
        }
    }

    @Test
    @DisplayName("Integracion: Login, presencia, creacion de sala, envio de mensajes privados y logout")
    public void testFullIntegrationFlow() throws Exception {
        ChatServicePrx chatService = ChatServicePrx.checkedCast(
                clientCommunicator.stringToProxy("ChatService:tcp -h 127.0.0.1 -p " + serverPort)
        );
        assertNotNull(chatService);

        ObjectAdapter clientAdapter = clientCommunicator.createObjectAdapterWithEndpoints("ClientAdapter", "tcp -h 127.0.0.1");

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        BlockingQueue<PrivateMessage> privateMessages = new LinkedBlockingQueue<>();

        ChatCallback cbServant = new ChatCallback() {
            @Override
            public void userConnected(String nickname, Current current) {
                events.add("connected:" + nickname);
            }

            @Override
            public void userDisconnected(String nickname, Current current) {
                events.add("disconnected:" + nickname);
            }

            @Override
            public void receivePrivateMessage(PrivateMessage msg, Current current) {
                privateMessages.add(msg);
            }

            @Override
            public void receiveRoomMessage(RoomMessage msg, Current current) {}
            @Override
            public void receiveFileChunk(String from, String room, FileChunk chunk, Current current) {}
            @Override
            public void incomingCall(String from, UdpEndpoint audio, Current current) {}
            @Override
            public void callAccepted(String from, UdpEndpoint audio, Current current) {}
            @Override
            public void callRejected(String from, Current current) {}
            @Override
            public void callEnded(String from, Current current) {}
            @Override
            public void roomCallStarted(String room, String by, Current current) {}
            @Override
            public void roomCallMemberJoined(String room, String nickname, Current current) {}
            @Override
            public void roomCallMemberLeft(String room, String nickname, Current current) {}
            @Override
            public void ping(Current current) {}
        };

        ChatCallbackPrx cbPrx = ChatCallbackPrx.uncheckedCast(
                clientAdapter.add(cbServant, Util.stringToIdentity(UUID.randomUUID().toString()))
        );
        clientAdapter.activate();

        // 1. Login
        chatService.login("alice", cbPrx);
        String[] users = chatService.listUsers();
        assertEquals(1, users.length);
        assertEquals("alice", users[0]);

        // 2. Crear y listar salas
        chatService.createRoom("general", "alice");
        String[] rooms = chatService.listRooms();
        assertTrue(rooms.length >= 1);
        assertEquals("general", rooms[0]);

        // 3. Enviar mensaje privado a si mismo
        chatService.sendPrivate("alice", "alice", "Hola a mi mismo");
        PrivateMessage received = privateMessages.poll(3, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("alice", received.from);
        assertEquals("Hola a mi mismo", received.text);

        // 4. Logout
        chatService.logout("alice");
        String[] usersAfter = chatService.listUsers();
        assertEquals(0, usersAfter.length);
    }
}
