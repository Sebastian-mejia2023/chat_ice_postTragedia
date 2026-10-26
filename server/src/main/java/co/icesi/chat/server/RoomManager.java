package co.icesi.chat.server;

import co.icesi.Chat.NotInRoomException;
import co.icesi.Chat.RoomAlreadyExistsException;
import co.icesi.Chat.RoomNotFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gestor concurrente y thread-safe de salas de chat y sus miembros.
 */
public class RoomManager {

    private static final Logger LOGGER = Logger.getLogger(RoomManager.class.getName());

    // Nombre de la sala -> Conjunto concurrente de nicknames
    private final ConcurrentHashMap<String, Set<String>> rooms = new ConcurrentHashMap<>();

    public void createRoom(String room, String creator) throws RoomAlreadyExistsException {
        if (room == null || room.trim().isEmpty()) {
            RoomAlreadyExistsException ex = new RoomAlreadyExistsException();
            ex.reason = "El nombre de la sala no puede estar vacio";
            throw ex;
        }

        Set<String> memberSet = ConcurrentHashMap.newKeySet();
        if (creator != null && !creator.trim().isEmpty()) {
            memberSet.add(creator);
        }

        Set<String> existing = rooms.putIfAbsent(room, memberSet);
        if (existing != null) {
            LOGGER.warning("Intento de creacion de sala duplicada: " + room);
            RoomAlreadyExistsException ex = new RoomAlreadyExistsException();
            ex.reason = "La sala '" + room + "' ya existe";
            throw ex;
        }

        LOGGER.info("Sala '" + room + "' creada exitosamente por: " + creator);
    }

    public List<String> listRooms() {
        return new ArrayList<>(rooms.keySet());
    }

    public void joinRoom(String room, String nickname) throws RoomNotFoundException {
        Set<String> members = rooms.get(room);
        if (members == null) {
            RoomNotFoundException ex = new RoomNotFoundException();
            ex.reason = "La sala '" + room + "' no existe";
            throw ex;
        }

        members.add(nickname);
        LOGGER.info("Usuario '" + nickname + "' se unio a la sala: " + room);
    }

    public void leaveRoom(String room, String nickname) throws RoomNotFoundException, NotInRoomException {
        Set<String> members = rooms.get(room);
        if (members == null) {
            RoomNotFoundException ex = new RoomNotFoundException();
            ex.reason = "La sala '" + room + "' no existe";
            throw ex;
        }

        boolean removed = members.remove(nickname);
        if (!removed) {
            NotInRoomException ex = new NotInRoomException();
            ex.reason = "El usuario '" + nickname + "' no pertenece a la sala '" + room + "'";
            throw ex;
        }

        LOGGER.info("Usuario '" + nickname + "' abandono la sala: " + room);
    }

    public boolean isMember(String room, String nickname) {
        Set<String> members = rooms.get(room);
        return members != null && members.contains(nickname);
    }

    public Set<String> getMembers(String room) {
        Set<String> members = rooms.get(room);
        if (members == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(members);
    }

    public void removeUserEverywhere(String nickname) {
        if (nickname == null) {
            return;
        }
        for (Set<String> members : rooms.values()) {
            members.remove(nickname);
        }
        LOGGER.info("Usuario '" + nickname + "' removido de todas las salas");
    }
}
