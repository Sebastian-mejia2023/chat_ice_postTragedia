package co.icesi.chat.client.audio;

import co.icesi.Chat.ChatServicePrx;
import co.icesi.Chat.UdpEndpoint;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas del ciclo de vida de llamadas del cliente con audio y servidor simulados.
 * Reproduce el bug reportado: /leavecall no abandonaba una llamada 1 a 1.
 */
public class VoiceCallManagerTest {

    @Test
    @DisplayName("/leavecall abandona una llamada 1 a 1 activa (regresion)")
    public void leaveCallEndsDirectCall() {
        RecordingChatService service = new RecordingChatService(9);
        VoiceCallManager manager = newManager(service, new FakeAudioSource(false));

        manager.setMyNickname("bob");
        manager.startDirectCall("alice");
        assertTrue(manager.isInCall());

        manager.leaveCall();

        assertFalse(manager.isInCall(), "el usuario sigue en la llamada 1 a 1");
        assertTrue(service.calls.contains("hangup"));
        Object[] hangupArgs = service.lastArgs("hangup");
        assertEquals("bob", hangupArgs[0]);
        assertEquals("alice", hangupArgs[1]);
    }

    @Test
    @DisplayName("/leavecall abandona la llamada grupal, envia BYE al relay y limpia el estado")
    public void leaveCallEndsRoomCallAndSendsBye() throws Exception {
        try (DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            relay.setSoTimeout(2000);
            RecordingChatService service = new RecordingChatService(relay.getLocalPort());
            VoiceCallManager manager = newManager(service, new FakeAudioSource(false));

            manager.setMyNickname("bob");
            manager.joinRoomCall("sala");
            assertTrue(manager.isInCall());

            AudioPacket hello = receive(relay);
            assertNotNull(hello, "no llego el HELLO al AudioRelay");
            assertEquals(AudioConstants.PACKET_TYPE_HELLO, hello.getType());
            assertEquals("bob", hello.getSenderNickname());

            manager.leaveCall();

            assertFalse(manager.isInCall(), "el usuario sigue en la llamada grupal");
            AudioPacket bye = receive(relay);
            assertNotNull(bye, "no llego el BYE al AudioRelay");
            assertEquals(AudioConstants.PACKET_TYPE_BYE, bye.getType());
            assertEquals("sala", bye.getRoom());
            assertTrue(service.calls.contains("leaveRoomCall"));
            assertTrue(Arrays.asList(service.lastArgs("leaveRoomCall")).containsAll(List.of("sala", "bob")));
        }
    }

    @Test
    @DisplayName("Si falla el audio local tras unirse, se hace rollback de la membresia en el servidor")
    public void failedAudioStartRollsBackServerMembership() {
        RecordingChatService service = new RecordingChatService(9);
        VoiceCallManager manager = newManager(service, new FakeAudioSource(true));

        manager.setMyNickname("bob");
        manager.joinRoomCall("sala");

        assertFalse(manager.isInCall());
        assertTrue(service.calls.contains("joinRoomCall"));
        assertTrue(service.calls.contains("leaveRoomCall"),
                "quedo un participante fantasma en la llamada del servidor");
    }

    private VoiceCallManager newManager(RecordingChatService service, AudioSource source) {
        return new VoiceCallManager(service.proxy(), new ConsolePrinter(), "127.0.0.1",
                () -> source, FakeAudioSink::new);
    }

    private AudioPacket receive(DatagramSocket socket) {
        byte[] buffer = new byte[2048];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(datagram);
            return PacketCodec.decode(datagram.getData(), datagram.getLength());
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeAudioSource implements AudioSource {
        private final boolean failStart;

        FakeAudioSource(boolean failStart) {
            this.failStart = failStart;
        }

        @Override
        public void start() throws IOException {
            if (failStart) {
                throw new IOException("microfono de prueba no disponible");
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return -1;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeAudioSink implements AudioSink {
        @Override
        public void start() {
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
        }

        @Override
        public void close() {
        }
    }

    /**
     * Proxy dinamico que evita depender de un servidor Ice real y registra cada invocacion remota.
     */
    private static final class RecordingChatService implements InvocationHandler {

        private final int relayPort;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<Object[]> args = new CopyOnWriteArrayList<>();

        RecordingChatService(int relayPort) {
            this.relayPort = relayPort;
        }

        ChatServicePrx proxy() {
            return (ChatServicePrx) Proxy.newProxyInstance(
                    ChatServicePrx.class.getClassLoader(),
                    new Class<?>[]{ChatServicePrx.class},
                    this);
        }

        Object[] lastArgs(String method) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).equals(method)) {
                    return args.get(i);
                }
            }
            return new Object[0];
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] methodArgs) {
            String name = method.getName();
            if (name.equals("toString")) {
                return "RecordingChatService";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == methodArgs[0];
            }

            calls.add(name);
            args.add(methodArgs != null ? methodArgs : new Object[0]);

            if (name.equals("joinRoomCall")) {
                return new UdpEndpoint("127.0.0.1", relayPort);
            }
            return null;
        }
    }
}
