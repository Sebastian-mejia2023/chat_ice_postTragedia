package co.icesi.chat.server;

import co.icesi.Chat.CallException;
import co.icesi.Chat.ChatCallbackPrx;
import co.icesi.Chat.ChatException;
import co.icesi.Chat.ChatService;
import co.icesi.Chat.FileChunk;
import co.icesi.Chat.NotInRoomException;
import co.icesi.Chat.PrivateMessage;
import co.icesi.Chat.RoomAlreadyExistsException;
import co.icesi.Chat.RoomMessage;
import co.icesi.Chat.RoomNotFoundException;
import co.icesi.Chat.TransferException;
import co.icesi.Chat.UdpEndpoint;
import co.icesi.Chat.UserAlreadyExistsException;
import co.icesi.Chat.UserNotFoundException;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;

import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Servant de Ice 3.7 que implementa el contrato co.icesi.Chat.ChatService.
 * Es completamente stateless: delega toda la coordinacion en componentes especializados y thread-safe.
 */
public class ChatServiceI implements ChatService {

    private static final Logger LOGGER = Logger.getLogger(ChatServiceI.class.getName());
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,20}$");

    private final UserRegistry userRegistry;
    private final RoomManager roomManager;
    private final CallManager callManager;
    private final Broadcaster broadcaster;
    private final FileRelayService fileRelayService;
    private final AudioRelay audioRelay;
    private final ClientReaper clientReaper;
    private final String configuredHost;
    private final String advertisedHost;

    public ChatServiceI(UserRegistry userRegistry, RoomManager roomManager,
                        CallManager callManager, Broadcaster broadcaster,
                        FileRelayService fileRelayService, AudioRelay audioRelay,
                        ClientReaper clientReaper, String configuredHost, String advertisedHost) {
        this.userRegistry = userRegistry;
        this.roomManager = roomManager;
        this.callManager = callManager;
        this.broadcaster = broadcaster;
        this.fileRelayService = fileRelayService;
        this.audioRelay = audioRelay;
        this.clientReaper = clientReaper;
        this.configuredHost = configuredHost;
        this.advertisedHost = advertisedHost;
    }

    @Override
    public void login(String nickname, ChatCallbackPrx cb, Current current)
            throws UserAlreadyExistsException, ChatException {
        if (nickname == null || !NICKNAME_PATTERN.matcher(nickname).matches()) {
            ChatException ex = new ChatException();
            ex.reason = "Formato de nickname invalido (debe ser alfanumerico entre 2 y 20 caracteres)";
            throw ex;
        }

        if (cb == null) {
            ChatException ex = new ChatException();
            ex.reason = "Se requiere un proxy de callback valido para iniciar sesion";
            throw ex;
        }

        UserSession session = new UserSession(nickname, cb, current.con);
        userRegistry.register(nickname, session);

        // Registro de callback de cierre a nivel de conexion para deteccion automatica de desconexion
        if (current.con != null && clientReaper != null) {
            current.con.setCloseCallback(con -> clientReaper.dropByConnection(con));
        }

        // Notificar presencia al resto de clientes conectados
        broadcaster.broadcastUserConnected(nickname);
        LOGGER.info("Sesion iniciada para el usuario: " + nickname);
    }

    @Override
    public void logout(String nickname, Current current) {
        if (clientReaper != null) {
            clientReaper.dropUser(nickname, "Logout explicito");
        }
    }

    @Override
    public String[] listUsers(Current current) {
        List<String> list = userRegistry.getAllNicknames();
        return list.toArray(new String[0]);
    }

    @Override
    public void sendPrivate(String from, String to, String text, Current current)
            throws UserNotFoundException, ChatException {
        if (text == null || text.trim().isEmpty()) {
            ChatException ex = new ChatException();
            ex.reason = "El mensaje no puede estar vacio";
            throw ex;
        }

        UserSession target = userRegistry.get(to);
        if (target == null) {
            UserNotFoundException ex = new UserNotFoundException();
            ex.reason = "Usuario destinatario '" + to + "' no existe o esta desconectado";
            throw ex;
        }

        PrivateMessage msg = new PrivateMessage(from, to, text, System.currentTimeMillis());
        broadcaster.sendPrivateMessage(target, msg);
    }

    @Override
    public void createRoom(String room, String creator, Current current)
            throws RoomAlreadyExistsException, ChatException {
        roomManager.createRoom(room, creator);
    }

    @Override
    public String[] listRooms(Current current) {
        List<String> list = roomManager.listRooms();
        return list.toArray(new String[0]);
    }

    @Override
    public void joinRoom(String room, String nickname, Current current)
            throws RoomNotFoundException, ChatException {
        roomManager.joinRoom(room, nickname);
    }

    @Override
    public void leaveRoom(String room, String nickname, Current current)
            throws RoomNotFoundException, NotInRoomException, ChatException {
        roomManager.leaveRoom(room, nickname);
    }

    @Override
    public void sendRoomMessage(String from, String room, String text, Current current)
            throws RoomNotFoundException, NotInRoomException, ChatException {
        if (!roomManager.listRooms().contains(room)) {
            RoomNotFoundException ex = new RoomNotFoundException();
            ex.reason = "La sala '" + room + "' no existe";
            throw ex;
        }

        if (!roomManager.isMember(room, from)) {
            NotInRoomException ex = new NotInRoomException();
            ex.reason = "No eres miembro de la sala '" + room + "'";
            throw ex;
        }

        RoomMessage msg = new RoomMessage(room, from, text, System.currentTimeMillis());
        Collection<String> members = roomManager.getMembers(room);
        broadcaster.broadcastRoomMessage(members, from, msg);
    }

    @Override
    public void sendFileChunkToUser(String from, String to, FileChunk chunk, Current current)
            throws UserNotFoundException, TransferException, ChatException {
        fileRelayService.sendChunkToUser(from, to, chunk);
    }

    @Override
    public void sendFileChunkToRoom(String from, String room, FileChunk chunk, Current current)
            throws RoomNotFoundException, NotInRoomException, TransferException, ChatException {
        fileRelayService.sendChunkToRoom(from, room, chunk);
    }

    @Override
    public void startCall(String from, String to, UdpEndpoint myAudio, Current current)
            throws UserNotFoundException, CallException, ChatException {
        UserSession callee = userRegistry.get(to);
        if (callee == null) {
            UserNotFoundException ex = new UserNotFoundException();
            ex.reason = "Destinatario de llamada '" + to + "' no encontrado";
            throw ex;
        }

        callManager.startDirectCall(from, to, myAudio);
        if (callee.getCallback() != null) {
            callee.getCallback().incomingCallAsync(from, myAudio);
        }
    }

    @Override
    public void acceptCall(String from, String caller, UdpEndpoint myAudio, Current current)
            throws CallException, ChatException {
        CallManager.DirectCall call = callManager.acceptDirectCall(from, caller, myAudio);
        UserSession callerSession = userRegistry.get(caller);
        if (callerSession != null && callerSession.getCallback() != null) {
            callerSession.getCallback().callAcceptedAsync(from, myAudio);
        }
    }

    @Override
    public void rejectCall(String from, String caller, Current current)
            throws CallException, ChatException {
        callManager.rejectDirectCall(from, caller);
        UserSession callerSession = userRegistry.get(caller);
        if (callerSession != null && callerSession.getCallback() != null) {
            callerSession.getCallback().callRejectedAsync(from);
        }
    }

    @Override
    public void hangup(String from, String peer, Current current) {
        CallManager.DirectCall call = callManager.hangup(from, peer);
        UserSession peerSession = userRegistry.get(peer);
        if (peerSession != null && peerSession.getCallback() != null) {
            peerSession.getCallback().callEndedAsync(from);
        }
    }

    @Override
    public UdpEndpoint joinRoomCall(String room, String nickname, UdpEndpoint myAudio, Current current)
            throws RoomNotFoundException, NotInRoomException, CallException, ChatException {
        if (!roomManager.listRooms().contains(room)) {
            RoomNotFoundException ex = new RoomNotFoundException();
            ex.reason = "La sala '" + room + "' no existe";
            throw ex;
        }

        if (!roomManager.isMember(room, nickname)) {
            NotInRoomException ex = new NotInRoomException();
            ex.reason = "Debes unirte a la sala de texto '" + room + "' antes de unirte a la llamada de voz";
            throw ex;
        }

        callManager.joinRoomCall(room, nickname);
        Collection<String> members = roomManager.getMembers(room);
        broadcaster.broadcastRoomCallMemberJoined(members, room, nickname);

        String relayHost = RelayHostResolver.resolve(
                advertisedHost, connectionLocalAddress(current), configuredHost);
        LOGGER.fine("AudioRelay anunciado a '" + nickname + "' en " + relayHost + ":" + audioRelay.getBoundPort());
        return new UdpEndpoint(relayHost, audioRelay.getBoundPort());
    }

    /**
     * Obtiene la IP local del socket TCP de la invocacion actual. Es la unica forma fiable de
     * anunciar una IP alcanzable cuando el servidor escucha en 0.0.0.0.
     */
    private String connectionLocalAddress(Current current) {
        if (current == null || current.con == null) {
            return null;
        }

        try {
            ConnectionInfo info = current.con.getInfo();
            if (info instanceof IPConnectionInfo) {
                return ((IPConnectionInfo) info).localAddress;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "No se pudo obtener la IP local de la conexion Ice", e);
        }
        return null;
    }

    @Override
    public void leaveRoomCall(String room, String nickname, Current current) {
        boolean removed = callManager.leaveRoomCall(room, nickname);
        // Limpiar siempre el relay: puede tener una entrada HELLO aunque el CallManager ya no lo tenga.
        audioRelay.removeParticipant(room, nickname);
        if (removed) {
            Collection<String> members = roomManager.getMembers(room);
            broadcaster.broadcastRoomCallMemberLeft(members, room, nickname);
        }
    }
}
