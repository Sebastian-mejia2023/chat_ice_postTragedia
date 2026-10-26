package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioConstants;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;

/**
 * Captura de microfono en tiempo real usando javax.sound.sampled.
 */
public class MicrophoneAudioSource implements AudioSource {

    private TargetDataLine line;

    @Override
    public void start() throws IOException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, AudioConstants.AUDIO_FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IOException("Microfono no soportado con el formato de audio configurado");
        }
        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(AudioConstants.AUDIO_FORMAT);
            line.start();
        } catch (LineUnavailableException e) {
            throw new IOException("Linea de microfono no disponible: " + e.getMessage(), e);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (line == null || !line.isOpen()) {
            return -1;
        }
        return line.read(buffer, offset, length);
    }

    @Override
    public void close() {
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
        }
    }
}
