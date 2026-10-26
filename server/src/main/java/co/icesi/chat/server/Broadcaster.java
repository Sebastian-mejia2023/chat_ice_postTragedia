package co.icesi.chat.server;

import co.icesi.Chat.ChatCallbackPrx;
import co.icesi.Chat.PrivateMessage;
import co.icesi.Chat.RoomMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Despachador asincrono de eventos y mensajes hacia los clientes conectados.
 * Garantiza que nunca se bloquee el servidor por clientes lentos o caidos.
 */
public class Broadcaster {

    private static final Logger LOGGER = Logger.getLogger(Broadcaster.class.getName());

    private final UserRegistry userRegistry;
    private ClientReaper clientReaper;

    public Broadcaster(UserRegistry userRegistry) {
        this.userRegistry = userRegistry;
    }

    public void setClientReaper(ClientReaper clientReaper) {
        this.clientReaper = clientReaper;
    }

    public void broadcastUserConnected(String newNickname) {
        List<UserSession> targets = new ArrayList<>(userRegistry.getAllSessions());
        for (UserSession s : targets) {
            ChatCallbackPrx cb = s.getCallback();
            if (cb != null) {
                cb.userConnectedAsync(newNickname).whenComplete((res, ex) -> {
                    if (ex != null) {
                        handleFailure(s.getNickname(), ex);
                    }
                });
            }
        }
    }

    public void broadcastUserDisconnected(String disconnectedNickname) {
        List<UserSession> targets = new ArrayList<>(userRegistry.getAllSessions());
        for (UserSession s : targets) {
            ChatCallbackPrx cb = s.getCallback();
            if (cb != null) {
                cb.userDisconnectedAsync(disconnectedNickname).whenComplete((res, ex) -> {
                    if (ex != null) {
                        handleFailure(s.getNickname(), ex);
                    }
                });
            }
        }
    }

    public void sendPrivateMessage(UserSession target, PrivateMessage msg) {
        ChatCallbackPrx cb = target.getCallback();
        if (cb != null) {
            cb.receivePrivateMessageAsync(msg).whenComplete((res, ex) -> {
                if (ex != null) {
                    handleFailure(target.getNickname(), ex);
                }
            });
        }
    }

    public void broadcastRoomMessage(Collection<String> members, String from, RoomMessage msg) {
        for (String member : members) {
            // Regla crucial: NO reenviar mensaje al emisor
            if (member.equals(from)) {
                continue;
            }
            UserSession s = userRegistry.get(member);
            if (s != null && s.getCallback() != null) {
                s.getCallback().receiveRoomMessageAsync(msg).whenComplete((res, ex) -> {
                    if (ex != null) {
                        handleFailure(member, ex);
                    }
                });
            }
        }
    }

    public void broadcastRoomCallMemberJoined(Collection<String> members, String room, String joinedUser) {
        for (String member : members) {
            if (member.equals(joinedUser)) {
                continue;
            }
            UserSession s = userRegistry.get(member);
            if (s != null && s.getCallback() != null) {
                s.getCallback().roomCallMemberJoinedAsync(room, joinedUser).whenComplete((res, ex) -> {
                    if (ex != null) {
                        handleFailure(member, ex);
                    }
                });
            }
        }
    }

    public void broadcastRoomCallMemberLeft(Collection<String> members, String room, String leftUser) {
        for (String member : members) {
            UserSession s = userRegistry.get(member);
            if (s != null && s.getCallback() != null) {
                s.getCallback().roomCallMemberLeftAsync(room, leftUser).whenComplete((res, ex) -> {
                    if (ex != null) {
                        handleFailure(member, ex);
                    }
                });
            }
        }
    }

    private void handleFailure(String nickname, Throwable ex) {
        LOGGER.log(Level.WARNING, "Fallo al enviar callback asincrono a '" + nickname + "': " + ex.getMessage());
        if (clientReaper != null) {
            clientReaper.dropUser(nickname, "Fallo de conexion en callback remoto");
        }
    }
}
