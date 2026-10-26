package co.icesi.chat.client.audio;

import java.util.Collection;

/**
 * Mezclador digital de audio PCM 16-bit Little-Endian con proteccion de saturacion (clipping).
 * Permite que varios participantes hablen concurrentemente en una llamada grupal sin distorsion.
 */
public final class AudioMixer {

    private AudioMixer() {
        // Prevenir instanciacion
    }

    /**
     * Mezcla multiples buffers PCM de igual tamano con suma aritmetica y saturacion.
     *
     * @param buffers lista de buffers de 16-bit PCM mono
     * @param length longitud en bytes del frame
     * @return buffer mezclado
     */
    public static byte[] mix(Collection<byte[]> buffers, int length) {
        if (buffers == null || buffers.isEmpty()) {
            return new byte[length];
        }

        if (buffers.size() == 1) {
            byte[] single = buffers.iterator().next();
            byte[] copy = new byte[length];
            System.arraycopy(single, 0, copy, 0, Math.min(single.length, length));
            return copy;
        }

        byte[] output = new byte[length];
        int numSamples = length / 2;

        for (int i = 0; i < numSamples; i++) {
            int byteIndex = i * 2;
            int sum = 0;

            for (byte[] buf : buffers) {
                if (byteIndex + 1 < buf.length) {
                    short sample = (short) ((buf[byteIndex] & 0xFF) | (buf[byteIndex + 1] << 8));
                    sum += sample;
                }
            }

            // Saturacion (clipping prevention)
            if (sum > Short.MAX_VALUE) {
                sum = Short.MAX_VALUE;
            } else if (sum < Short.MIN_VALUE) {
                sum = Short.MIN_VALUE;
            }

            output[byteIndex] = (byte) (sum & 0xFF);
            output[byteIndex + 1] = (byte) ((sum >> 8) & 0xFF);
        }

        return output;
    }
}
