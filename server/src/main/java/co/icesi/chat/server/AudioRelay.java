package co.icesi.chat.server;

import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servidor de Retransmision de Audio UDP (SFU Ligero).
 * Recibe flujos continuos de voz de cada participante de una conferencia y los retransmite
 * a los demas integrantes de la sala de audio, suprimiendo de forma estricta el eco al emisor.
 */
public class AudioRelay implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(AudioRelay.class.getName());
    private static final int BUFFER_SIZE = 2048;

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread workerThread;

    // Sala -> (Nickname -> Endpoint UDP real del cliente)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, InetSocketAddress>> roomParticipants = new ConcurrentHashMap<>();

    public AudioRelay(int port) {
        this.port = port;
    }

    public synchronized void start() throws SocketException {
        if (running) {
            return;
        }

        socket = new DatagramSocket(port);
        running = true;
        workerThread = new Thread(this, "AudioRelay-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
        LOGGER.info("AudioRelay UDP iniciado en puerto: " + socket.getLocalPort());
    }

    public synchronized void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        LOGGER.info("AudioRelay UDP detenido");
    }

    public int getBoundPort() {
        return socket != null ? socket.getLocalPort() : port;
    }

    public void removeParticipant(String room, String nickname) {
        ConcurrentHashMap<String, InetSocketAddress> participants = roomParticipants.get(room);
        if (participants != null) {
            participants.remove(nickname);
            if (participants.isEmpty()) {
                roomParticipants.remove(room);
            }
        }
    }

    public void removeParticipantEverywhere(String nickname) {
        for (Map.Entry<String, ConcurrentHashMap<String, InetSocketAddress>> entry : roomParticipants.entrySet()) {
            entry.getValue().remove(nickname);
            if (entry.getValue().isEmpty()) {
                roomParticipants.remove(entry.getKey());
            }
        }
    }

    @Override
    public void run() {
        byte[] receiveBuffer = new byte[BUFFER_SIZE];

        while (running && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(packet);

                AudioPacket audioPacket;
                try {
                    audioPacket = PacketCodec.decode(packet.getData(), packet.getLength());
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Paquete UDP corrupto o no reconocido ignorado");
                    continue;
                }

                String room = audioPacket.getRoom();
                String nick = audioPacket.getSenderNickname();
                InetSocketAddress senderAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());

                if (room == null || room.isEmpty()) {
                    continue;
                }

                ConcurrentHashMap<String, InetSocketAddress> participants =
                        roomParticipants.computeIfAbsent(room, k -> new ConcurrentHashMap<>());

                if (audioPacket.isHello()) {
                    participants.put(nick, senderAddress);
                    LOGGER.info("AudioRelay: registrado HELLO de '" + nick + "' en sala '" + room + "' desde " + senderAddress);
                } else if (audioPacket.isBye()) {
                    participants.remove(nick);
                    if (participants.isEmpty()) {
                        roomParticipants.remove(room, participants);
                    }
                    LOGGER.info("AudioRelay: registrado BYE de '" + nick + "' en sala '" + room + "'");
                } else if (audioPacket.isAudio()) {
                    // Solo se retransmite audio de remitentes registrados via HELLO.
                    // Asi un AUDIO tardio tras el BYE no re-registra al usuario que ya salio.
                    if (!participants.containsKey(nick)) {
                        continue;
                    }
                    // Actualizar direccion por si cambio puerto/NAT
                    participants.put(nick, senderAddress);

                    // Reenviar a todos los demas en la sala (NUNCA de vuelta al emisor)
                    byte[] data = packet.getData();
                    int len = packet.getLength();

                    for (Map.Entry<String, InetSocketAddress> entry : participants.entrySet()) {
                        String targetNick = entry.getKey();
                        InetSocketAddress targetAddr = entry.getValue();

                        // Regla crucial: NO eco al emisor
                        if (!targetNick.equals(nick)) {
                            try {
                                DatagramPacket forwardPacket = new DatagramPacket(data, len, targetAddr);
                                socket.send(forwardPacket);
                            } catch (IOException e) {
                                LOGGER.log(Level.FINE, "Error retransmitiendo audio a " + targetNick + ": " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                if (!running) {
                    break;
                }
                LOGGER.log(Level.WARNING, "Socket exception en AudioRelay: " + e.getMessage());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error de I/O en AudioRelay: " + e.getMessage());
            }
        }
    }
}
