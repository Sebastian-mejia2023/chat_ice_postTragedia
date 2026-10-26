package co.icesi.chat.server;

import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el relay UDP: sin eco al emisor, BYE efectivo y audio de remitentes no registrados ignorado.
 */
public class AudioRelayTest {

    private static final String ROOM = "sala";
    private static final int BUFFER_SIZE = 2048;

    private AudioRelay relay;
    private InetSocketAddress relayAddress;

    @BeforeEach
    public void setUp() throws Exception {
        relay = new AudioRelay(0);
        relay.start();
        relayAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getBoundPort());
    }

    @AfterEach
    public void tearDown() {
        if (relay != null) {
            relay.stop();
        }
    }

    @Test
    @DisplayName("A habla: B y C reciben el audio, A no recibe eco")
    public void relaysToOtherParticipantsWithoutEcho() throws Exception {
        try (DatagramSocket alice = newClient();
             DatagramSocket bob = newClient();
             DatagramSocket carol = newClient()) {

            sendHello(alice, "alice");
            sendHello(bob, "bob");
            sendHello(carol, "carol");

            AudioPacket voice = audio("alice", 1, "hola");
            assertTrue(sendUntilReceived(alice, voice, bob, 2000), "bob no recibio el audio de alice");
            assertTrue(sendUntilReceived(alice, voice, carol, 2000), "carol no recibio el audio de alice");
            assertNull(receiveWithin(alice, 300), "alice recibio eco de su propio audio");
        }
    }

    @Test
    @DisplayName("BYE saca a B sin cortar a C y un AUDIO tardio no lo re-registra")
    public void byeRemovesParticipantAndLateAudioDoesNotReRegister() throws Exception {
        try (DatagramSocket alice = newClient();
             DatagramSocket bob = newClient();
             DatagramSocket carol = newClient()) {

            sendHello(alice, "alice");
            sendHello(bob, "bob");
            sendHello(carol, "carol");

            // Asegura que alice, bob y carol ya esten registrados en el relay.
            assertTrue(sendUntilReceived(alice, audio("alice", 1, "hola"), bob, 2000));
            assertTrue(sendUntilReceived(bob, audio("bob", 2, "hola"), carol, 2000));
            drain(carol);

            // BYE y AUDIO salen del mismo socket: UDP conserva el orden, el relay debe ignorar el AUDIO.
            sendBye(bob, "bob");
            sendFrom(bob, audio("bob", 3, "tardio"));
            assertNull(receiveWithin(carol, 500),
                    "carol recibio audio tardio de bob: el AUDIO lo re-registro despues del BYE");

            // carol sigue recibiendo audio de alice: salir de B no corta a C.
            drain(bob);
            assertTrue(sendUntilReceived(alice, audio("alice", 4, "sigo"), carol, 2000),
                    "carol dejo de recibir audio tras el BYE de bob");
            assertNull(receiveWithin(bob, 300), "bob siguio recibiendo audio tras su BYE");
        }
    }

    @Test
    @DisplayName("Audio de un remitente sin HELLO se descarta")
    public void ignoresAudioFromUnregisteredSender() throws Exception {
        try (DatagramSocket alice = newClient();
             DatagramSocket bob = newClient();
             DatagramSocket intruder = newClient()) {

            sendHello(alice, "alice");
            sendHello(bob, "bob");
            assertTrue(sendUntilReceived(alice, audio("alice", 1, "hola"), bob, 2000));
            drain(bob);

            sendFrom(intruder, audio("intruso", 2, "no registrado"));
            assertNull(receiveWithin(bob, 400), "bob recibio audio de un remitente sin HELLO");

            assertTrue(sendUntilReceived(alice, audio("alice", 3, "hola"), bob, 2000));
            assertNull(receiveWithin(intruder, 300), "el intruso recibio audio sin estar registrado");
        }
    }

    private DatagramSocket newClient() throws IOException {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(500);
        return socket;
    }

    private void sendHello(DatagramSocket origin, String nickname) throws IOException {
        sendFrom(origin, new AudioPacket(
                AudioConstants.PACKET_TYPE_HELLO,
                (short) nickname.hashCode(),
                0,
                System.currentTimeMillis(),
                ROOM,
                nickname,
                new byte[0]
        ));
    }

    private void sendBye(DatagramSocket origin, String nickname) throws IOException {
        sendFrom(origin, new AudioPacket(
                AudioConstants.PACKET_TYPE_BYE,
                (short) nickname.hashCode(),
                0,
                System.currentTimeMillis(),
                ROOM,
                nickname,
                new byte[0]
        ));
    }

    private AudioPacket audio(String nickname, int sequence, String text) {
        return new AudioPacket(
                AudioConstants.PACKET_TYPE_AUDIO,
                (short) nickname.hashCode(),
                sequence,
                System.currentTimeMillis(),
                ROOM,
                nickname,
                text.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void sendFrom(DatagramSocket origin, AudioPacket packet) throws IOException {
        byte[] encoded = PacketCodec.encode(packet);
        origin.send(new DatagramPacket(encoded, encoded.length, relayAddress));
    }

    private boolean sendUntilReceived(DatagramSocket sender, AudioPacket packet,
                                      DatagramSocket receiver, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            sendFrom(sender, packet);
            if (receiveWithin(receiver, 50) != null) {
                return true;
            }
        }
        return false;
    }

    private AudioPacket receiveWithin(DatagramSocket socket, int timeoutMs) {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        try {
            socket.setSoTimeout(timeoutMs);
            socket.receive(datagram);
            return PacketCodec.decode(datagram.getData(), datagram.getLength());
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void drain(DatagramSocket socket) {
        while (receiveWithin(socket, 100) != null) {
            // descarta paquetes en vuelo de pruebas anteriores
        }
    }
}
