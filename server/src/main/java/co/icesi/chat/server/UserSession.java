package co.icesi.chat.server;

import co.icesi.Chat.ChatCallbackPrx;
import com.zeroc.Ice.Connection;

/**
 * Representa la sesion activa de un usuario conectado al servidor Ice.
 */
public final class UserSession {

    private final String nickname;
    private final ChatCallbackPrx callback;
    private final Connection connection;
    private volatile long lastSeen;

    public UserSession(String nickname, ChatCallbackPrx callback, Connection connection) {
        this.nickname = nickname;
        this.callback = callback;
        this.connection = connection;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getNickname() {
        return nickname;
    }

    public ChatCallbackPrx getCallback() {
        return callback;
    }

    public Connection getConnection() {
        return connection;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
}
