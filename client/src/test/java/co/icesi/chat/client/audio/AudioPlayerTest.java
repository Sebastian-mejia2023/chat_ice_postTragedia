package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de la mezcla real de voz en AudioPlayer: buffer por emisor, clipping,
 * descarte del frame mas viejo y recepcion UDP sin hardware de audio.
 */
public class AudioPlayerTest {

    @Test
    @DisplayName("AudioPlayer mezcla dos emisores con AudioMixer antes de escribir")
    public void mixesTwoSendersBeforeWriting() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.enqueue(audioPacket("alice", 1000));
            player.enqueue(audioPacket("bob", 2000));

            player.mixAndPlayOnce();

            byte[] written = sink.lastWrite();
            assertNotNull(written);
            assertEquals(3000, sampleAt(written, 0));
        }
    }

    @Test
    @DisplayName("La mezcla satura en Short.MAX_VALUE cuando dos voces se suman")
    public void clipsWhenVoicesOverflow() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.enqueue(audioPacket("alice", 30000));
            player.enqueue(audioPacket("bob", 30000));

            player.mixAndPlayOnce();

            assertEquals(Short.MAX_VALUE, sampleAt(sink.lastWrite(), 0));
        }
    }

    @Test
    @DisplayName("El buffer por emisor se limita a dos frames y descarta el mas viejo")
    public void dropsOldestFrameWhenSenderBufferIsFull() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.enqueue(audioPacket("alice", 1));
            player.enqueue(audioPacket("alice", 2));
            player.enqueue(audioPacket("alice", 3));

            player.mixAndPlayOnce();

            assertEquals(2, sampleAt(sink.lastWrite(), 0),
                    "debe reproducir el frame siguiente y descartar el mas viejo");
        }
    }

    @Test
    @DisplayName("Paquetes HELLO, vacios o de otro tipo no se reproducen")
    public void ignoresPacketsThatAreNotAudio() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.enqueue(new AudioPacket(
                    AudioConstants.PACKET_TYPE_HELLO, (short) 1, 0, 0L, "sala", "alice",
                    frameWithSample(5000)));
            player.enqueue(new AudioPacket(
                    AudioConstants.PACKET_TYPE_AUDIO, (short) 2, 0, 0L, "sala", "bob",
                    new byte[0]));

            player.mixAndPlayOnce();

            assertTrue(isSilence(sink.lastWrite()), "no debio reproducir audio");
        }
    }

    @Test
    @DisplayName("Sin frames pendientes se escribe silencio de 640 bytes")
    public void writesSilenceWhenNoFramesAreAvailable() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.mixAndPlayOnce();

            byte[] written = sink.lastWrite();
            assertNotNull(written);
            assertEquals(AudioConstants.FRAME_PAYLOAD_SIZE, written.length);
            assertTrue(isSilence(written));
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("AudioPlayer recibe UDP, ignora basura y reproduce el frame valido")
    public void receivesUdpIgnoresMalformedAndWritesAudio() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket sender = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);
            InetSocketAddress target = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), socket.getLocalPort());

            player.start();
            try {
                byte[] garbage = new byte[]{1, 2, 3};
                sender.send(new DatagramPacket(garbage, garbage.length, target));

                byte[] payload = frameWithSample(1234);
                byte[] encoded = PacketCodec.encode(new AudioPacket(
                        AudioConstants.PACKET_TYPE_AUDIO, (short) 7, 1, 0L, "sala", "alice", payload));
                sender.send(new DatagramPacket(encoded, encoded.length, target));

                byte[] written = awaitNonSilentWrite(sink, 3000);
                assertNotNull(written, "no llego audio valido al sink");
                assertArrayEquals(payload, written);
            } finally {
                player.stop();
            }
        }
    }

    @Test
    @DisplayName("stop cierra el sink y se puede llamar sin iniciar")
    public void stopClosesSinkAndIsIdempotent() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FakeAudioSink sink = new FakeAudioSink();
            AudioPlayer player = new AudioPlayer(socket, sink);

            player.stop();
            player.stop();

            assertTrue(sink.closed);
        }
    }

    private byte[] awaitNonSilentWrite(FakeAudioSink sink, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            byte[] written = sink.writes.poll(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            if (written != null && !isSilence(written)) {
                return written;
            }
        }
        return null;
    }

    private AudioPacket audioPacket(String sender, int firstSample) {
        return new AudioPacket(AudioConstants.PACKET_TYPE_AUDIO, (short) sender.hashCode(),
                0, 0L, "sala", sender, frameWithSample(firstSample));
    }

    private byte[] frameWithSample(int sample) {
        byte[] frame = new byte[AudioConstants.FRAME_PAYLOAD_SIZE];
        frame[0] = (byte) (sample & 0xFF);
        frame[1] = (byte) ((sample >> 8) & 0xFF);
        return frame;
    }

    private int sampleAt(byte[] frame, int sampleIndex) {
        int offset = sampleIndex * 2;
        return (short) ((frame[offset] & 0xFF) | (frame[offset + 1] << 8));
    }

    private boolean isSilence(byte[] frame) {
        if (frame == null) {
            return false;
        }
        for (byte value : frame) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class FakeAudioSink implements AudioSink {

        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private volatile byte[] lastWritten;
        private volatile boolean closed;

        @Override
        public void start() {
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            byte[] copy = Arrays.copyOfRange(buffer, offset, offset + length);
            lastWritten = copy;
            writes.add(copy);
        }

        @Override
        public void close() {
            closed = true;
        }

        byte[] lastWrite() {
            return lastWritten;
        }
    }
}
