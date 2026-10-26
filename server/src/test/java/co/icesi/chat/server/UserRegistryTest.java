package co.icesi.chat.server;

import co.icesi.Chat.UserAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class UserRegistryTest {

    private UserRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new UserRegistry();
    }

    @Test
    @DisplayName("200 hilos concurrentes intentan registrar el mismo nickname: exactamente 1 exito")
    public void testConcurrentRegistrationSameNickname() throws InterruptedException {
        int threadCount = 200;
        String targetNickname = "alice";

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UserSession session = new UserSession(targetNickname, null, null);
                    registry.register(targetNickname, session);
                    successCount.incrementAndGet();
                } catch (UserAlreadyExistsException e) {
                    duplicateCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Disparar todos los hilos simultaneamente
        finishLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactamente 1 registro debio ser exitoso");
        assertEquals(threadCount - 1, duplicateCount.get(), "Los demas hilos debieron recibir UserAlreadyExistsException");
        assertEquals(1, registry.size());
        assertTrue(registry.contains(targetNickname));
    }

    @Test
    @DisplayName("Registro de usuarios distintos y desregistro ordenado")
    public void testRegisterAndUnregister() throws Exception {
        registry.register("bob", new UserSession("bob", null, null));
        registry.register("carol", new UserSession("carol", null, null));

        assertEquals(2, registry.size());
        assertTrue(registry.contains("bob"));
        assertTrue(registry.contains("carol"));

        UserSession removed = registry.unregister("bob");
        assertNotNull(removed);
        assertEquals("bob", removed.getNickname());
        assertFalse(registry.contains("bob"));
        assertEquals(1, registry.size());
    }
}
