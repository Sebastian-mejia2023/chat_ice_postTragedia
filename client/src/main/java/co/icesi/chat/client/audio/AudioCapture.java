package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Hilo dedicado a la captura de muestras de microfono y transmision UDP continua.
 */
public class AudioCapture implements Runnable {

    private final AudioSource source;
    private final DatagramSocket socket;
    private final InetSocketAddress targetAddress;
    private final String room;
    private final String nickname;
    private final short senderId;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private Thread workerThread;
    private int sequenceNumber = 0;

    public AudioCapture(AudioSource source, DatagramSocket socket,
                        InetSocketAddress targetAddress, String room,
                        String nickname, short senderId) {
        this.source = source;
        this.socket = socket;
        this.targetAddress = targetAddress;
        this.room = room != null ? room : "";
        this.nickname = nickname;
        this.senderId = senderId;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        source.start();
        running = true;
        workerThread = new Thread(this, "AudioCapture-" + nickname);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        source.close();
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    @Override
    public void run() {
        byte[] frameBuffer = new byte[AudioConstants.FRAME_PAYLOAD_SIZE];

        while (running && !socket.isClosed()) {
            try {
                int read = 0;
                while (read < frameBuffer.length && running) {
                    int r = source.read(frameBuffer, read, frameBuffer.length - read);
                    if (r < 0) {
                        break;
                    }
                    read += r;
                }

                if (read <= 0 || !running) {
                    break;
                }

                // Si esta silenciado (Mute), no transmite paquetes por la red
                if (muted) {
                    continue;
                }

                AudioPacket packet = new AudioPacket(
                        AudioConstants.PACKET_TYPE_AUDIO,
                        senderId,
                        sequenceNumber++,
                        System.currentTimeMillis(),
                        room,
                        nickname,
                        frameBuffer
                );

                byte[] encoded = PacketCodec.encode(packet);
                DatagramPacket datagram = new DatagramPacket(encoded, encoded.length, targetAddress);
                socket.send(datagram);
            } catch (Exception ignored) {
                if (!running) {
                    break;
                }
            }
        }
    }
}
