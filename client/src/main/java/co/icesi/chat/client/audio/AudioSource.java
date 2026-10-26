package co.icesi.chat.client.audio;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interfaz que abstrae la captura de muestras de audio PCM.
 * Permite desacoplar el hardware real de pruebas unitarias o entornos headless.
 */
public interface AudioSource extends Closeable {
    void start() throws IOException;
    int read(byte[] buffer, int offset, int length) throws IOException;
    @Override
    void close();
}
