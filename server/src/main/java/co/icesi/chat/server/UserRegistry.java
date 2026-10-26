package co.icesi.chat.server;

import co.icesi.Chat.UserAlreadyExistsException;
import com.zeroc.Ice.Connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registro concurrente y thread-safe de usuarios y sus proxies de retorno.
 */
public class UserRegistry {

    private static final Logger LOGGER = Logger.getLogger(UserRegistry.class.getName());

    private final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registra un usuario de forma atomica.
     *
     * @param nickname identificador unico del usuario
     * @param session sesion asociada
     * @throws UserAlreadyExistsException si el nickname ya esta en uso
     */
    public void register(String nickname, UserSession session) throws UserAlreadyExistsException {
        if (nickname == null || nickname.trim().isEmpty()) {
            UserAlreadyExistsException ex = new UserAlreadyExistsException();
            ex.reason = "Nickname no puede estar vacio";
            throw ex;
        }

        UserSession existing = sessions.putIfAbsent(nickname, session);
        if (existing != null) {
            LOGGER.warning("Intento de login duplicado rechazado para nickname: " + nickname);
            UserAlreadyExistsException ex = new UserAlreadyExistsException();
            ex.reason = "El nickname '" + nickname + "' ya se encuentra en uso";
            throw ex;
        }

        LOGGER.info("Usuario registrado exitosamente: " + nickname);
    }

    public UserSession unregister(String nickname) {
        if (nickname == null) {
            return null;
        }
        UserSession removed = sessions.remove(nickname);
        if (removed != null) {
            LOGGER.info("Usuario desregistrado: " + nickname);
        }
        return removed;
    }

    public UserSession get(String nickname) {
        return nickname != null ? sessions.get(nickname) : null;
    }

    public boolean contains(String nickname) {
        return nickname != null && sessions.containsKey(nickname);
    }

    public List<String> getAllNicknames() {
        return new ArrayList<>(sessions.keySet());
    }

    public Collection<UserSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    public UserSession findByConnection(Connection connection) {
        if (connection == null) {
            return null;
        }
        for (UserSession s : sessions.values()) {
            if (connection.equals(s.getConnection())) {
                return s;
            }
        }
        return null;
    }
}
