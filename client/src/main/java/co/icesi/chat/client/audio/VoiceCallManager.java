package co.icesi.chat.client.audio;

import co.icesi.Chat.ChatServicePrx;
import co.icesi.Chat.UdpEndpoint;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.client.util.ErrorMessages;
import co.icesi.chat.client.util.NetworkUtil;
import co.icesi.chat.common.AudioConstants;
import co.icesi.chat.common.AudioPacket;
import co.icesi.chat.common.PacketCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 * Gestor del ciclo de vida de llamadas de voz UDP en el cliente (1 a 1 y conferencias grupales).
 */
public class VoiceCallManager {

    private final ChatServicePrx chatService;
    private final ConsolePrinter printer;
    private final String serverHost;
    private final Supplier<AudioSource> sourceFactory;
    private final Supplier<AudioSink> sinkFactory;

    private String myNickname;
    private DatagramSocket udpSocket;
    private AudioCapture audioCapture;
    private AudioPlayer audioPlayer;

    private volatile String activeDirectPeer;
    private volatile String pendingIncomingCaller;
    private volatile UdpEndpoint pendingIncomingEndpoint;
    private volatile String activeRoomCall;
    private volatile UdpEndpoint activeRoomRelay;

    public VoiceCallManager(ChatServicePrx chatService, ConsolePrinter printer, String serverHost) {
        this(chatService, printer, serverHost, MicrophoneAudioSource::new, SpeakerAudioSink::new);
    }

    VoiceCallManager(ChatServicePrx chatService, ConsolePrinter printer, String serverHost,
                     Supplier<AudioSource> sourceFactory, Supplier<AudioSink> sinkFactory) {
        this.chatService = chatService;
        this.printer = printer;
        this.serverHost = serverHost;
        this.sourceFactory = sourceFactory;
        this.sinkFactory = sinkFactory;
    }

    public void setMyNickname(String myNickname) {
        this.myNickname = myNickname;
    }

    public synchronized void startDirectCall(String targetUser) {
        if (isInCall()) {
            printer.printError("Ya te encuentras en una llamada activa");
            return;
        }

        try {
            ensureUdpSocket();
            String localIp = NetworkUtil.getLocalLanIp(serverHost);
            UdpEndpoint myEndpoint = new UdpEndpoint(localIp, udpSocket.getLocalPort());

            activeDirectPeer = targetUser;
            chatService.startCall(myNickname, targetUser, myEndpoint);
            printer.printCall("Llamando a " + targetUser + "... esperando respuesta");
        } catch (Exception e) {
            cleanupCall();
            printer.printError("Error al iniciar llamada: " + ErrorMessages.describe(e));
        }
    }

    public synchronized void onIncomingCall(String from, UdpEndpoint audio) {
        this.pendingIncomingCaller = from;
        this.pendingIncomingEndpoint = audio;
        printer.printCall("¡Llamada entrante de " + from + "! Escribe /accept para contestar o /reject para rechazar.");
    }

    public synchronized void acceptCall() {
        if (pendingIncomingCaller == null) {
            printer.printError("No tienes llamadas entrantes pendientes");
            return;
        }

        try {
            ensureUdpSocket();
            String localIp = NetworkUtil.getLocalLanIp(serverHost);
            UdpEndpoint myEndpoint = new UdpEndpoint(localIp, udpSocket.getLocalPort());

            String caller = pendingIncomingCaller;
            UdpEndpoint callerEndpoint = pendingIncomingEndpoint;
            pendingIncomingCaller = null;
            pendingIncomingEndpoint = null;
            activeDirectPeer = caller;

            chatService.acceptCall(myNickname, caller, myEndpoint);

            // Iniciar flujo de audio hacia el caller
            InetSocketAddress target = new InetSocketAddress(callerEndpoint.host, callerEndpoint.port);
            startAudioStreams(target, "", (short) 1);

            printer.printCall("Llamada 1 a 1 conectada con " + caller + " [UDP activo en puerto " + udpSocket.getLocalPort() + "]");
        } catch (Exception e) {
            cleanupCall();
            printer.printError("Error al contestar llamada: " + ErrorMessages.describe(e));
        }
    }

    public synchronized void onCallAccepted(String callee, UdpEndpoint calleeAudio) {
        try {
            activeDirectPeer = callee;
            InetSocketAddress target = new InetSocketAddress(calleeAudio.host, calleeAudio.port);
            startAudioStreams(target, "", (short) 1);
            printer.printCall("¡Llamada aceptada por " + callee + "! Conectado via UDP [puerto local " + udpSocket.getLocalPort() + "]");
        } catch (Exception e) {
            cleanupCall();
            printer.printError("Error al establecer flujo de audio con " + callee + ": " + ErrorMessages.describe(e));
        }
    }

    public synchronized void rejectCall() {
        if (pendingIncomingCaller == null) {
            printer.printError("No tienes llamadas entrantes pendientes");
            return;
        }

        try {
            String caller = pendingIncomingCaller;
            pendingIncomingCaller = null;
            pendingIncomingEndpoint = null;
            chatService.rejectCall(myNickname, caller);
            printer.printCall("Llamada de " + caller + " rechazada.");
        } catch (Exception e) {
            printer.printError("Error al rechazar llamada: " + ErrorMessages.describe(e));
        }
    }

    public synchronized void onCallRejected(String caller) {
        printer.printCall("El usuario " + caller + " rechazo la llamada.");
        cleanupCall();
    }

    public synchronized void hangup() {
        if (activeDirectPeer != null) {
            String peer = activeDirectPeer;
            try {
                chatService.hangup(myNickname, peer);
            } catch (Exception ignored) {
            }
            printer.printCall("Llamada finalizada con " + peer);
            cleanupCall();
        } else if (activeRoomCall != null) {
            leaveRoomCall();
        } else {
            printer.printError("No hay ninguna llamada activa para colgar");
        }
    }

    /**
     * Abandona la llamada activa sin importar su tipo (1 a 1 o grupal). Es el comando /leavecall.
     */
    public synchronized void leaveCall() {
        if (activeDirectPeer != null) {
            String peer = activeDirectPeer;
            try {
                chatService.hangup(myNickname, peer);
            } catch (Exception ignored) {
            }
            printer.printCall("Has abandonado la llamada 1 a 1 con " + peer);
            cleanupCall();
        } else if (activeRoomCall != null) {
            leaveRoomCall();
        } else {
            printer.printError("No hay ninguna llamada activa para abandonar");
        }
    }

    public synchronized void onCallEnded(String from) {
        printer.printCall("El usuario " + from + " ha colgado la llamada.");
        cleanupCall();
    }

    public synchronized void joinRoomCall(String room) {
        if (isInCall()) {
            printer.printError("Ya te encuentras en una llamada activa");
            return;
        }

        try {
            ensureUdpSocket();
            String localIp = NetworkUtil.getLocalLanIp(serverHost);
            UdpEndpoint myEndpoint = new UdpEndpoint(localIp, udpSocket.getLocalPort());

            UdpEndpoint relayEndpoint = chatService.joinRoomCall(room, myNickname, myEndpoint);
            activeRoomCall = room;
            activeRoomRelay = relayEndpoint;

            InetSocketAddress relayAddress = new InetSocketAddress(relayEndpoint.host, relayEndpoint.port);
            startAudioStreams(relayAddress, room, (short) myNickname.hashCode());
            sendControlPacket(AudioConstants.PACKET_TYPE_HELLO, relayAddress, room);
            printer.printCall("Te has unido a la llamada grupal de la sala #" + room + " [AudioRelay " + relayAddress + "]");
        } catch (Exception e) {
            boolean joinedOnServer = activeRoomCall != null;
            cleanupCall();
            if (joinedOnServer) {
                // Rollback: si el audio local fallo, el servidor no debe dejarnos como participante fantasma.
                try {
                    chatService.leaveRoomCall(room, myNickname);
                } catch (Exception ignored) {
                }
            }
            printer.printError("Error al unirse a llamada grupal: " + ErrorMessages.describe(e));
        }
    }

    public synchronized void leaveRoomCall() {
        if (activeRoomCall == null) {
            printer.printError("No estas en ninguna llamada grupal activa");
            return;
        }

        String room = activeRoomCall;
        InetSocketAddress relayAddress = activeRoomRelay != null
                ? new InetSocketAddress(activeRoomRelay.host, activeRoomRelay.port)
                : null;

        // Detener la captura ANTES de avisar: evita que un AUDIO tardio nos re-registre en el relay.
        stopCapture();

        try {
            if (relayAddress != null && udpSocket != null && !udpSocket.isClosed()) {
                sendControlPacket(AudioConstants.PACKET_TYPE_BYE, relayAddress, room);
            }
            chatService.leaveRoomCall(room, myNickname);
        } catch (Exception e) {
            printer.printError("No se pudo notificar la salida al servidor: " + ErrorMessages.describe(e));
        }

        printer.printCall("Has salido de la llamada grupal de la sala #" + room);
        cleanupCall();
    }

    public synchronized void setMuted(boolean muted) {
        if (audioCapture != null) {
            audioCapture.setMuted(muted);
            printer.printCall(muted ? "Microfono SILENCIADO (Mute activado)" : "Microfono ACTIVADO (Unmute)");
        } else {
            printer.printError("No hay ninguna llamada en curso");
        }
    }

    public boolean isInCall() {
        return activeDirectPeer != null || activeRoomCall != null;
    }

    private void ensureUdpSocket() throws Exception {
        if (udpSocket == null || udpSocket.isClosed()) {
            udpSocket = new DatagramSocket(0); // Puerto efimero disponible
        }
    }

    private void startAudioStreams(InetSocketAddress target, String room, short senderId) throws Exception {
        AudioSource source = sourceFactory.get();
        AudioSink sink = sinkFactory.get();

        audioCapture = new AudioCapture(source, udpSocket, target, room, myNickname, senderId);
        audioPlayer = new AudioPlayer(udpSocket, sink);

        audioCapture.start();
        audioPlayer.start();
    }

    private void stopCapture() {
        if (audioCapture != null) {
            audioCapture.stop();
            audioCapture = null;
        }
    }

    private void sendControlPacket(byte type, InetSocketAddress relayAddress, String room) throws Exception {
        AudioPacket packet = new AudioPacket(
                type,
                (short) myNickname.hashCode(),
                0,
                System.currentTimeMillis(),
                room,
                myNickname,
                new byte[0]
        );
        byte[] encoded = PacketCodec.encode(packet);
        udpSocket.send(new DatagramPacket(encoded, encoded.length, relayAddress));
    }

    public synchronized void cleanupCall() {
        activeDirectPeer = null;
        pendingIncomingCaller = null;
        pendingIncomingEndpoint = null;
        activeRoomCall = null;
        activeRoomRelay = null;

        stopCapture();
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }
    }
}
