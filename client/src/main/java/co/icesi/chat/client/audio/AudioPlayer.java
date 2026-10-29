package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receptor de audio UDP y reproductor de voz.
 *
 * <p>Existe para desacoplar la red del altavoz: un hilo recibe y decodifica los
 * datagramas, mientras un segundo hilo mezcla cada 20 ms un frame por emisor con
 * {@link AudioMixer} y lo escribe en {@link AudioSink}. Asi dos personas pueden
 * hablar al mismo tiempo en una llamada grupal sin que sus frames se pisen.</p>
 *
 * <p>Cada emisor tiene un <b>jitter buffer</b>: la reproduccion de ese emisor no
 * empieza hasta acumular {@value #DEFAULT_PREBUFFER_FRAMES} frames (60 ms) y se
 * permiten hasta {@value #DEFAULT_MAX_FRAMES} (120 ms). Asi el audio no se corta cuando
 * los paquetes UDP llegan con jitter (Wi-Fi, varias maquinas). Si el buffer se vacia
 * (perdida de paquetes o emisor en mute) se vuelve a acumular antes de reanudar, y si
 * se llena se descarta el frame mas viejo para no acumular latencia. Cuando no hay
 * audio se escribe silencio para mantener el ritmo del dispositivo de salida.</p>
 */
public class AudioPlayer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(AudioPlayer.class.getName());

    private static final int RECEIVE_BUFFER_SIZE = 2048;
    /** Frames a acumular por emisor antes de empezar a reproducirlo (3 x 20 ms = 60 ms). */
    static final int DEFAULT_PREBUFFER_FRAMES = 3;
    /** Maximo de frames por emisor (6 x 20 ms = 120 ms); al llenarse se descarta el mas viejo. */
    static final int DEFAULT_MAX_FRAMES = 6;

    /** Cola de un emisor y su estado de reproduccion. Se accede siempre bajo synchronized. */
    private static final class SenderStream {
        final Deque<byte[]> frames = new ArrayDeque<>();
        boolean playing = false;
    }

    private final DatagramSocket socket;
    private final AudioSink sink;
    private final int prebufferFrames;
    private final int maxFramesPerSender;
    private final ConcurrentHashMap<String, SenderStream> framesBySender = new ConcurrentHashMap<>();
    private final AtomicBoolean playbackErrorLogged = new AtomicBoolean(false);

    private volatile boolean running = false;
    private Thread workerThread;
    private ScheduledExecutorService mixer;

    public AudioPlayer(DatagramSocket socket, AudioSink sink) {
        this(socket, sink, DEFAULT_PREBUFFER_FRAMES, DEFAULT_MAX_FRAMES);
    }

    AudioPlayer(DatagramSocket socket, AudioSink sink, int prebufferFrames, int maxFramesPerSender) {
        this.socket = socket;
        this.sink = sink;
        this.prebufferFrames = Math.max(1, prebufferFrames);
        this.maxFramesPerSender = Math.max(this.prebufferFrames, maxFramesPerSender);
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        sink.start();
        framesBySender.clear();
        playbackErrorLogged.set(false);
        running = true;

        workerThread = new Thread(this, "AudioPlayer-Worker");
        workerThread.setDaemon(true);
        workerThread.start();

        mixer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "AudioPlayer-Mixer");
            thread.setDaemon(true);
            return thread;
        });
        mixer.scheduleAtFixedRate(this::mixAndPlaySafely,
                AudioConstants.FRAME_DURATION_MS,
                AudioConstants.FRAME_DURATION_MS,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        ScheduledExecutorService currentMixer;
        Thread currentWorker;

        synchronized (this) {
            running = false;
            currentMixer = mixer;
            mixer = null;
            currentWorker = workerThread;
            workerThread = null;
            framesBySender.clear();
        }

        if (currentWorker != null) {
            currentWorker.interrupt();
        }
        if (currentMixer != null) {
            currentMixer.shutdownNow();
            try {
                currentMixer.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        sink.close();
    }

    @Override
    public void run() {
        byte[] receiveBuf = new byte[RECEIVE_BUFFER_SIZE];

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

                enqueue(audioPacket);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
            }
        }
    }

    /**
     * Encola un frame de audio para su emisor. Visible para pruebas deterministas.
     */
    void enqueue(AudioPacket audioPacket) {
        if (audioPacket == null || !audioPacket.isAudio()) {
            return;
        }

        byte[] payload = audioPacket.getPayload();
        if (payload.length == 0) {
            return;
        }
        if (payload.length > AudioConstants.FRAME_PAYLOAD_SIZE) {
            payload = Arrays.copyOf(payload, AudioConstants.FRAME_PAYLOAD_SIZE);
        }

        String sender = audioPacket.getSenderNickname();
        if (sender.isEmpty()) {
            sender = "senderId:" + audioPacket.getSenderId();
        }

        SenderStream stream = framesBySender.computeIfAbsent(sender, key -> new SenderStream());

        synchronized (stream) {
            if (stream.frames.size() >= maxFramesPerSender) {
                stream.frames.pollFirst();
            }
            stream.frames.addLast(payload);
            if (!stream.playing && stream.frames.size() >= prebufferFrames) {
                stream.playing = true;
            }
        }
    }

    /**
     * Toma como maximo un frame por emisor ya "en reproduccion" y devuelve la mezcla de 640 bytes.
     * Visible para pruebas deterministas; no escribe en el altavoz.
     */
    byte[] mixNextFrame() {
        List<byte[]> frames = new ArrayList<>();

        for (SenderStream stream : framesBySender.values()) {
            byte[] frame = null;
            synchronized (stream) {
                if (stream.playing) {
                    frame = stream.frames.pollFirst();
                    if (frame == null) {
                        // Buffer vacio: volver a acumular antes de reanudar (evita tartamudeo).
                        stream.playing = false;
                    }
                }
            }
            if (frame != null) {
                frames.add(frame);
            }
        }

        if (frames.isEmpty()) {
            return new byte[AudioConstants.FRAME_PAYLOAD_SIZE];
        }
        return AudioMixer.mix(frames, AudioConstants.FRAME_PAYLOAD_SIZE);
    }

    /**
     * Ejecuta un ciclo completo de mezcla y escritura. Visible para pruebas deterministas.
     */
    void mixAndPlayOnce() {
        byte[] mixed = mixNextFrame();
        sink.write(mixed, 0, mixed.length);
    }

    private void mixAndPlaySafely() {
        if (!running) {
            return;
        }
        try {
            mixAndPlayOnce();
        } catch (RuntimeException e) {
            if (running && playbackErrorLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Fallo al reproducir el audio mezclado", e);
            }
        }
    }
}
