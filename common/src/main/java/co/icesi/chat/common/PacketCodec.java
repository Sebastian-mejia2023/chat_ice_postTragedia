package co.icesi.chat.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Codificador y decodificador binario para paquetes de voz UDP.
 * Formato estructurado y eficiente:
 * [1 byte tipo][2 bytes senderId][4 bytes seq][8 bytes timestamp]
 * [1 byte roomLen][roomBytes][1 byte nickLen][nickBytes]
 * [2 bytes payloadLen][payloadBytes]
 */
public final class PacketCodec {

    private PacketCodec() {
        // Prevenir instanciacion
    }

    public static byte[] encode(AudioPacket packet) {
        byte[] roomBytes = packet.getRoom().getBytes(StandardCharsets.UTF_8);
        byte[] nickBytes = packet.getSenderNickname().getBytes(StandardCharsets.UTF_8);
        byte[] payload = packet.getPayload();

        int totalLen = 1 + 2 + 4 + 8 + 1 + roomBytes.length + 1 + nickBytes.length + 2 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);

        buffer.put(packet.getType());
        buffer.putShort(packet.getSenderId());
        buffer.putInt(packet.getSequenceNumber());
        buffer.putLong(packet.getTimestamp());

        buffer.put((byte) roomBytes.length);
        buffer.put(roomBytes);

        buffer.put((byte) nickBytes.length);
        buffer.put(nickBytes);

        buffer.putShort((short) payload.length);
        buffer.put(payload);

        return buffer.array();
    }

    public static AudioPacket decode(byte[] data, int length) {
        if (data == null || length < 1 + 2 + 4 + 8 + 1 + 1 + 2) {
            throw new IllegalArgumentException("Paquete UDP incompleto o longitud invalida: " + length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        byte type = buffer.get();
        short senderId = buffer.getShort();
        int seq = buffer.getInt();
        long timestamp = buffer.getLong();

        int roomLen = buffer.get() & 0xFF;
        if (buffer.remaining() < roomLen) {
            throw new IllegalArgumentException("Buffer insuficiente para room");
        }
        byte[] roomBytes = new byte[roomLen];
        buffer.get(roomBytes);
        String room = new String(roomBytes, StandardCharsets.UTF_8);

        int nickLen = buffer.get() & 0xFF;
        if (buffer.remaining() < nickLen) {
            throw new IllegalArgumentException("Buffer insuficiente para nickname");
        }
        byte[] nickBytes = new byte[nickLen];
        buffer.get(nickBytes);
        String nickname = new String(nickBytes, StandardCharsets.UTF_8);

        if (buffer.remaining() < 2) {
            throw new IllegalArgumentException("Buffer insuficiente para longitud de payload");
        }
        int payloadLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() < payloadLen) {
            throw new IllegalArgumentException("Payload truncado en paquete UDP");
        }
        byte[] payload = new byte[payloadLen];
        buffer.get(payload);

        return new AudioPacket(type, senderId, seq, timestamp, room, nickname, payload);
    }
}
