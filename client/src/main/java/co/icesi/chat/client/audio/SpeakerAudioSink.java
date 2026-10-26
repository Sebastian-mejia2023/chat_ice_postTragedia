package co.icesi.chat.client.audio;

import co.icesi.chat.common.AudioConstants;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Reproduccion de audio en altavoces usando javax.sound.sampled.
 */
public class SpeakerAudioSink implements AudioSink {

    private SourceDataLine line;

    @Override
    public void start() {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, AudioConstants.AUDIO_FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            return;
        }
        try {
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(AudioConstants.AUDIO_FORMAT);
            line.start();
        } catch (LineUnavailableException ignored) {
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        if (line != null && line.isOpen()) {
            line.write(buffer, offset, length);
        }
    }

    @Override
    public void close() {
        if (line != null) {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
        }
    }
}
