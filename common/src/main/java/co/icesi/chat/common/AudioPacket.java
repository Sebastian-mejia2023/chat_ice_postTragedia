package co.icesi.chat.common;

import java.util.Arrays;

/**
 * Modelo inmutable de un paquete de voz UDP.
 */
public final class AudioPacket {

    private final byte type;
    private final short senderId;
    private final int sequenceNumber;
    private final long timestamp;
    private final String room;
    private final String senderNickname;
    private final byte[] payload;

    public AudioPacket(byte type, short senderId, int sequenceNumber, long timestamp,
                       String room, String senderNickname, byte[] payload) {
        this.type = type;
        this.senderId = senderId;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.room = room != null ? room : "";
        this.senderNickname = senderNickname != null ? senderNickname : "";
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    public byte getType() {
        return type;
    }

    public short getSenderId() {
        return senderId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getRoom() {
        return room;
    }

    public String getSenderNickname() {
        return senderNickname;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public boolean isHello() {
        return type == AudioConstants.PACKET_TYPE_HELLO;
    }

    public boolean isAudio() {
        return type == AudioConstants.PACKET_TYPE_AUDIO;
    }

    public boolean isBye() {
        return type == AudioConstants.PACKET_TYPE_BYE;
    }

    @Override
    public String toString() {
        return "AudioPacket{" +
                "type=" + type +
                ", senderId=" + senderId +
                ", seq=" + sequenceNumber +
                ", room='" + room + '\'' +
                ", sender='" + senderNickname + '\'' +
                ", payloadLen=" + payload.length +
                '}';
    }
}
