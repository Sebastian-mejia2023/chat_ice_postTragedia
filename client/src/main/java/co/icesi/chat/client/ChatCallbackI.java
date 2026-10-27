package co.icesi.chat.client;

import co.icesi.Chat.ChatCallback;
import co.icesi.Chat.FileChunk;
import co.icesi.Chat.PrivateMessage;
import co.icesi.Chat.RoomMessage;
import co.icesi.Chat.UdpEndpoint;
import co.icesi.chat.client.audio.VoiceCallManager;
import co.icesi.chat.client.file.FileReceiver;
import co.icesi.chat.client.ui.ConsolePrinter;
import com.zeroc.Ice.Current;

/**
 * Servant de callback en el cliente para recibir notificaciones asincronas desde el servidor Ice.
 * Regla de diseno: metodos rapidos y no bloqueantes; delegan inmediatamente el trabajo pesado.
 */
public class ChatCallbackI implements ChatCallback {

    private final ConsolePrinter printer;
    private final FileReceiver fileReceiver;
    private final VoiceCallManager voiceCallManager;

    public ChatCallbackI(ConsolePrinter printer, FileReceiver fileReceiver, VoiceCallManager voiceCallManager) {
        this.printer = printer;
        this.fileReceiver = fileReceiver;
        this.voiceCallManager = voiceCallManager;
    }

    @Override
    public void userConnected(String nickname, Current current) {
        printer.printSystem("Usuario conectado: " + nickname);
    }

    @Override
    public void userDisconnected(String nickname, Current current) {
        printer.printSystem("Usuario desconectado: " + nickname);
    }

    @Override
    public void receivePrivateMessage(PrivateMessage msg, Current current) {
        printer.printPrivate(msg.from, msg.text);
    }

    @Override
    public void receiveRoomMessage(RoomMessage msg, Current current) {
        printer.printRoom(msg.room, msg.from, msg.text);
    }

    @Override
    public void receiveFileChunk(String from, String room, FileChunk chunk, Current current) {
        fileReceiver.onChunkReceived(from, room, chunk);
    }

    @Override
    public void incomingCall(String from, UdpEndpoint audio, Current current) {
        voiceCallManager.onIncomingCall(from, audio);
    }

    @Override
    public void callAccepted(String from, UdpEndpoint audio, Current current) {
        voiceCallManager.onCallAccepted(from, audio);
    }

    @Override
    public void callRejected(String from, Current current) {
        voiceCallManager.onCallRejected(from);
    }

    @Override
    public void callEnded(String from, Current current) {
        voiceCallManager.onCallEnded(from);
    }

    @Override
    public void roomCallStarted(String room, String by, Current current) {
        printer.printCall("Iniciada llamada de voz en la sala #" + room + " por " + by);
    }

    @Override
    public void roomCallMemberJoined(String room, String nickname, Current current) {
        printer.printCall(nickname + " se ha unido a la llamada de voz de la sala #" + room);
    }

    @Override
    public void roomCallMemberLeft(String room, String nickname, Current current) {
        printer.printCall(nickname + " ha salido de la llamada de voz de la sala #" + room);
    }

    @Override
    public void ping(Current current) {
        // Respuesta rapida para chequear salud de conexion
    }
}
