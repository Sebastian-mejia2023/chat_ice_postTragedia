package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Hilo dedicado a la recepcion de datagramas de audio UDP y reproduccion en altavoz.
 */
public class AudioPlayer implements Runnable {

    private static final int BUFFER_SIZE = 2048;

    private final DatagramSocket socket;
    private final AudioSink sink;
    private volatile boolean running = false;
    private Thread workerThread;

    public AudioPlayer(DatagramSocket socket, AudioSink sink) {
        this.socket = socket;
        this.sink = sink;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        sink.start();
        running = true;
        workerThread = new Thread(this, "AudioPlayer-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        sink.close();
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Override
    public void run() {
        byte[] receiveBuf = new byte[BUFFER_SIZE];

        while (running && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(receiveBuf, receiveBuf.length);
                socket.receive(packet);

                AudioPacket audioPacket;
                try {
                    audioPacket = PacketCodec.decode(packet.getData(), packet.getLength());
                } catch (Exception e) {
                    continue;
                }

                if (audioPacket.isAudio()) {
                    byte[] pcm = audioPacket.getPayload();
                    if (pcm.length > 0) {
                        sink.write(pcm, 0, pcm.length);
                    }
                }
            } catch (Exception ignored) {
                if (!running) {
                    break;
                }
            }
        }
    }
}
