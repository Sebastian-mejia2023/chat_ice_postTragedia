package co.icesi.chat.client.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AudioMixerTest {

    @Test
    @DisplayName("Mezcla de dos senales de audio PCM sin saturacion")
    public void testMixTwoSignals() {
        // Frame de 4 bytes (2 muestras de 16 bits)
        // Muestra 1: 1000 + 2000 = 3000
        // Muestra 2: -500 + 1500 = 1000
        byte[] bufA = new byte[]{
                (byte) (1000 & 0xFF), (byte) ((1000 >> 8) & 0xFF),
                (byte) (-500 & 0xFF), (byte) ((-500 >> 8) & 0xFF)
        };
        byte[] bufB = new byte[]{
                (byte) (2000 & 0xFF), (byte) ((2000 >> 8) & 0xFF),
                (byte) (1500 & 0xFF), (byte) ((1500 >> 8) & 0xFF)
        };

        byte[] mixed = AudioMixer.mix(Arrays.asList(bufA, bufB), 4);

        short s1 = (short) ((mixed[0] & 0xFF) | (mixed[1] << 8));
        short s2 = (short) ((mixed[2] & 0xFF) | (mixed[3] << 8));

        assertEquals(3000, s1);
        assertEquals(1000, s2);
    }

    @Test
    @DisplayName("Saturacion (Clipping): previene desbordamiento aritmetico ante volumen alto")
    public void testClippingProtection() {
        // Muestra: 25000 + 20000 = 45000 (desborda Short.MAX_VALUE = 32767)
        byte[] bufA = new byte[]{
                (byte) (25000 & 0xFF), (byte) ((25000 >> 8) & 0xFF)
        };
        byte[] bufB = new byte[]{
                (byte) (20000 & 0xFF), (byte) ((20000 >> 8) & 0xFF)
        };

        byte[] mixed = AudioMixer.mix(Arrays.asList(bufA, bufB), 2);

        short result = (short) ((mixed[0] & 0xFF) | (mixed[1] << 8));
        assertEquals(Short.MAX_VALUE, result, "Debe saturar en Short.MAX_VALUE sin pasar a negativo");
    }
}
