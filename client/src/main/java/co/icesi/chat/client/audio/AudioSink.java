package co.icesi.chat.client.audio;

import java.io.Closeable;

/**
 * Interfaz que abstrae la reproduccion de muestras de audio PCM.
 */
public interface AudioSink extends Closeable {
    void start();
    void write(byte[] buffer, int offset, int length);
    @Override
    void close();
}
