package co.icesi.chat.server;

import co.icesi.Chat.CallException;
import co.icesi.Chat.UdpEndpoint;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gestor de estado de llamadas de voz (1 a 1 y conferencias grupales).
 */
public class CallManager {

    private static final Logger LOGGER = Logger.getLogger(CallManager.class.getName());

    public enum CallState {
        PENDING,
        ACTIVE
    }

    public static final class DirectCall {
        public final String caller;
        public final String callee;
        public final UdpEndpoint callerEndpoint;
        public volatile UdpEndpoint calleeEndpoint;
        public volatile CallState state;

        public DirectCall(String caller, String callee, UdpEndpoint callerEndpoint) {
            this.caller = caller;
            this.callee = callee;
            this.callerEndpoint = callerEndpoint;
            this.state = CallState.PENDING;
        }
    }

    // Usuario -> Llamada directa activa/pendiente
    private final ConcurrentHashMap<String, DirectCall> userDirectCalls = new ConcurrentHashMap<>();

    // Sala -> Conjunto de nicknames en llamada grupal
    private final ConcurrentHashMap<String, Set<String>> roomCalls = new ConcurrentHashMap<>();

    // Usuario -> Sala en la que participa en llamada
    private final ConcurrentHashMap<String, String> userRoomCalls = new ConcurrentHashMap<>();

    public synchronized DirectCall startDirectCall(String from, String to, UdpEndpoint myAudio) throws CallException {
        if (userDirectCalls.containsKey(from) || userRoomCalls.containsKey(from)) {
            CallException ex = new CallException();
            ex.reason = "Ya te encuentras en una llamada activa";
            throw ex;
        }

        if (userDirectCalls.containsKey(to) || userRoomCalls.containsKey(to)) {
            CallException ex = new CallException();
            ex.reason = "El usuario '" + to + "' se encuentra ocupado en otra llamada";
            throw ex;
        }

        DirectCall call = new DirectCall(from, to, myAudio);
        userDirectCalls.put(from, call);
        userDirectCalls.put(to, call);

        LOGGER.info("Llamada directa iniciada: " + from + " -> " + to);
        return call;
    }

    public synchronized DirectCall acceptDirectCall(String from, String caller, UdpEndpoint myAudio) throws CallException {
        DirectCall call = userDirectCalls.get(from);
        if (call == null || !call.caller.equals(caller) || !call.callee.equals(from)) {
            CallException ex = new CallException();
            ex.reason = "No existe una llamada pendiente de '" + caller + "'";
            throw ex;
        }

        call.calleeEndpoint = myAudio;
        call.state = CallState.ACTIVE;
        LOGGER.info("Llamada directa aceptada: " + caller + " <-> " + from);
        return call;
    }

    public synchronized DirectCall rejectDirectCall(String from, String caller) throws CallException {
        DirectCall call = userDirectCalls.get(from);
        if (call == null || !call.caller.equals(caller) || !call.callee.equals(from)) {
            CallException ex = new CallException();
            ex.reason = "No existe una llamada pendiente de '" + caller + "'";
            throw ex;
        }

        userDirectCalls.remove(call.caller);
        userDirectCalls.remove(call.callee);
        LOGGER.info("Llamada directa rechazada: " + from + " rechazo a " + caller);
        return call;
    }

    public synchronized DirectCall hangup(String from, String peer) {
        DirectCall call = userDirectCalls.remove(from);
        if (call != null) {
            userDirectCalls.remove(call.caller);
            userDirectCalls.remove(call.callee);
            LOGGER.info("Llamada directa finalizada entre " + call.caller + " y " + call.callee);
        }
        return call;
    }

    public DirectCall getDirectCall(String user) {
        return userDirectCalls.get(user);
    }

    public synchronized void joinRoomCall(String room, String nickname) throws CallException {
        if (userDirectCalls.containsKey(nickname)) {
            CallException ex = new CallException();
            ex.reason = "Ya estas en una llamada 1 a 1 activa";
            throw ex;
        }

        String currentRoomCall = userRoomCalls.get(nickname);
        if (currentRoomCall != null && !currentRoomCall.equals(room)) {
            CallException ex = new CallException();
            ex.reason = "Ya te encuentras en la llamada de la sala '" + currentRoomCall + "'";
            throw ex;
        }

        Set<String> participants = roomCalls.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet());
        participants.add(nickname);
        userRoomCalls.put(nickname, room);
        LOGGER.info("Usuario '" + nickname + "' se unio a la llamada grupal de sala: " + room);
    }

    public synchronized boolean leaveRoomCall(String room, String nickname) {
        Set<String> participants = roomCalls.get(room);
        boolean removed = false;
        if (participants != null) {
            removed = participants.remove(nickname);
            if (participants.isEmpty()) {
                roomCalls.remove(room);
            }
        }
        userRoomCalls.remove(nickname);
        LOGGER.info("Usuario '" + nickname + "' salio de la llamada grupal de sala: " + room);
        return removed;
    }

    public Set<String> getRoomCallParticipants(String room) {
        Set<String> set = roomCalls.get(room);
        return set != null ? new HashSet<>(set) : Collections.emptySet();
    }

    public synchronized void removeUserEverywhere(String nickname) {
        DirectCall direct = userDirectCalls.remove(nickname);
        if (direct != null) {
            userDirectCalls.remove(direct.caller);
            userDirectCalls.remove(direct.callee);
        }

        String room = userRoomCalls.remove(nickname);
        if (room != null) {
            Set<String> p = roomCalls.get(room);
            if (p != null) {
                p.remove(nickname);
                if (p.isEmpty()) {
                    roomCalls.remove(room);
                }
            }
        }
    }
}
