package co.icesi.chat.common;

import javax.sound.sampled.AudioFormat;

/**
 * Constantes compartidas para el sistema de audio y transferencia de archivos.
 */
public final class AudioConstants {

    private AudioConstants() {
        // Prevenir instanciacion
    }

    // ---------- Configuracion de Fragmentacion (Chunking) ----------
    /**
     * Tamano de fragmento para transferencia de archivos multimedia sobre Ice (48 KB).
     * Se mantiene por debajo del Ice.MessageSizeMax por defecto (1024 KB).
     */
    public static final int CHUNK_SIZE = 48 * 1024;

    // ---------- Configuracion de Audio PCM (Voz UDP) ----------
    public static final float SAMPLE_RATE = 16000.0f;     // 16 kHz
    public static final int SAMPLE_SIZE_IN_BITS = 16;     // 16 bits por muestra
    public static final int CHANNELS = 1;                 // Mono
    public static final boolean SIGNED = true;            // Con signo
    public static final boolean BIG_ENDIAN = false;       // Little-endian

    /**
     * Duracion en milisegundos de cada frame de audio transmitido por UDP.
     */
    public static final int FRAME_DURATION_MS = 20;

    /**
     * Muestras por frame a 16 kHz en 20 ms = 320 muestras.
     * En 16 bits (2 bytes/muestra) = 640 bytes de payload de audio.
     */
    public static final int SAMPLES_PER_FRAME = (int) (SAMPLE_RATE * FRAME_DURATION_MS / 1000);
    public static final int FRAME_PAYLOAD_SIZE = SAMPLES_PER_FRAME * (SAMPLE_SIZE_IN_BITS / 8);

    /**
     * Formato estandar javax.sound.sampled compartido.
     */
    public static final AudioFormat AUDIO_FORMAT = new AudioFormat(
            SAMPLE_RATE,
            SAMPLE_SIZE_IN_BITS,
            CHANNELS,
            SIGNED,
            BIG_ENDIAN
    );

    // ---------- Protocolo de Datagramas UDP ----------
    public static final byte PACKET_TYPE_HELLO = 0;
    public static final byte PACKET_TYPE_AUDIO = 1;
    public static final byte PACKET_TYPE_BYE   = 2;
}
