package co.icesi.chat.server;

import co.icesi.Chat.NotInRoomException;
import co.icesi.Chat.RoomAlreadyExistsException;
import co.icesi.Chat.RoomNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class RoomManagerTest {

    private RoomManager roomManager;

    @BeforeEach
    public void setUp() {
        roomManager = new RoomManager();
    }

    @Test
    @DisplayName("Creacion de salas y rechazo de duplicados")
    public void testCreateRoom() throws Exception {
        roomManager.createRoom("general", "alice");
        assertTrue(roomManager.listRooms().contains("general"));
        assertTrue(roomManager.isMember("general", "alice"));

        assertThrows(RoomAlreadyExistsException.class, () -> roomManager.createRoom("general", "bob"));
    }

    @Test
    @DisplayName("Unirse y salir de salas con validaciones estrictas")
    public void testJoinAndLeave() throws Exception {
        roomManager.createRoom("devs", "alice");
        roomManager.joinRoom("devs", "bob");

        assertTrue(roomManager.isMember("devs", "bob"));
        Set<String> members = roomManager.getMembers("devs");
        assertEquals(2, members.size());

        roomManager.leaveRoom("devs", "bob");
        assertFalse(roomManager.isMember("devs", "bob"));

        assertThrows(NotInRoomException.class, () -> roomManager.leaveRoom("devs", "bob"));
        assertThrows(RoomNotFoundException.class, () -> roomManager.joinRoom("inexistente", "bob"));
    }

    @Test
    @DisplayName("Concurrencia: multiples hilos se unen simultaneamente a la misma sala")
    public void testConcurrentJoin() throws Exception {
        roomManager.createRoom("stress", "alice");
        int threadCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String user = "user_" + i;
            executor.submit(() -> {
                try {
                    roomManager.joinRoom("stress", user);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Set<String> members = roomManager.getMembers("stress");
        assertEquals(threadCount + 1, members.size(), "Todos los usuarios debieron ser agregados concurrentemente");
    }

    @Test
    @DisplayName("Remover usuario de todas las salas al desconectarse")
    public void testRemoveUserEverywhere() throws Exception {
        roomManager.createRoom("r1", "alice");
        roomManager.createRoom("r2", "alice");
        roomManager.joinRoom("r1", "bob");
        roomManager.joinRoom("r2", "bob");

        roomManager.removeUserEverywhere("bob");
        assertFalse(roomManager.isMember("r1", "bob"));
        assertFalse(roomManager.isMember("r2", "bob"));
        assertTrue(roomManager.isMember("r1", "alice"));
    }
}
