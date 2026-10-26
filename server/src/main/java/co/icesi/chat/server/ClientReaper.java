package co.icesi.chat.server;

import com.zeroc.Ice.Connection;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Detector y recolector de clientes caidos o desconectados abruptamente.
 */
public class ClientReaper {

    private static final Logger LOGGER = Logger.getLogger(ClientReaper.class.getName());

    private final UserRegistry userRegistry;
    private final RoomManager roomManager;
    private final CallManager callManager;
    private final Broadcaster broadcaster;
    private final AudioRelay audioRelay;

    private ScheduledExecutorService scheduler;

    public ClientReaper(UserRegistry userRegistry, RoomManager roomManager,
                        CallManager callManager, Broadcaster broadcaster,
                        AudioRelay audioRelay) {
        this.userRegistry = userRegistry;
        this.roomManager = roomManager;
        this.callManager = callManager;
        this.broadcaster = broadcaster;
        this.audioRelay = audioRelay;
    }

    public synchronized void startHealthCheck(long intervalSeconds) {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientReaper-HealthCheck");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::performPingCheck, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("ClientReaper health check iniciado cada " + intervalSeconds + " s");
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public void dropUser(String nickname, String reason) {
        if (nickname == null) {
            return;
        }

        UserSession session = userRegistry.unregister(nickname);
        if (session != null) {
            LOGGER.info("ClientReaper: removiendo usuario '" + nickname + "'. Motivo: " + reason);
            roomManager.removeUserEverywhere(nickname);
            callManager.removeUserEverywhere(nickname);
            if (audioRelay != null) {
                audioRelay.removeParticipantEverywhere(nickname);
            }
            broadcaster.broadcastUserDisconnected(nickname);
        }
    }

    public void dropByConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        UserSession s = userRegistry.findByConnection(connection);
        if (s != null) {
            dropUser(s.getNickname(), "Cierre abrupto de conexion TCP");
        }
    }

    private void performPingCheck() {
        for (UserSession s : userRegistry.getAllSessions()) {
            if (s.getCallback() != null) {
                s.getCallback().pingAsync().whenComplete((res, ex) -> {
                    if (ex != null) {
                        dropUser(s.getNickname(), "Fallo de ping / no responde");
                    } else {
                        s.updateLastSeen();
                    }
                });
            }
        }
    }
}
