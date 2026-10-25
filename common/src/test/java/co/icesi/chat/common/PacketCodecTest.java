package co.icesi.chat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PacketCodecTest {

    @Test
    @DisplayName("Codificacion y decodificacion de paquetes UDP preservando campos intactos")
    public void testEncodeDecodeRoundTrip() {
        byte[] originalPayload = new byte[640];
        for (int i = 0; i < originalPayload.length; i++) {
            originalPayload[i] = (byte) (i % 127);
        }

        AudioPacket packet = new AudioPacket(
                AudioConstants.PACKET_TYPE_AUDIO,
                (short) 42,
                101,
                1698000000000L,
                "sala_general",
                "carlos",
                originalPayload
        );

        byte[] encoded = PacketCodec.encode(packet);
        assertNotNull(encoded);
        assertTrue(encoded.length > originalPayload.length);

        AudioPacket decoded = PacketCodec.decode(encoded, encoded.length);

        assertEquals(packet.getType(), decoded.getType());
        assertEquals(packet.getSenderId(), decoded.getSenderId());
        assertEquals(packet.getSequenceNumber(), decoded.getSequenceNumber());
        assertEquals(packet.getTimestamp(), decoded.getTimestamp());
        assertEquals(packet.getRoom(), decoded.getRoom());
        assertEquals(packet.getSenderNickname(), decoded.getSenderNickname());
        assertArrayEquals(packet.getPayload(), decoded.getPayload());
    }

    @Test
    @DisplayName("Manejo de paquetes corruptos o incompletos")
    public void testDecodeCorruptedPacket() {
        byte[] tiny = new byte[5];
        assertThrows(IllegalArgumentException.class, () -> PacketCodec.decode(tiny, tiny.length));
    }
}
