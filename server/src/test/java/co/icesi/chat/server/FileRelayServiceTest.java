package co.icesi.chat.server;

import co.icesi.Chat.FileChunk;
import co.icesi.Chat.TransferException;
import co.icesi.Chat.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica las validaciones defensivas del relay de archivos en el servidor.
 */
public class FileRelayServiceTest {

    private FileRelayService relayService;

    @BeforeEach
    public void setUp() {
        relayService = new FileRelayService(new UserRegistry(), new RoomManager());
    }

    private FileChunk chunk(String fileName, long totalSize, int chunkIndex, int totalChunks, int dataSize) {
        return new FileChunk("transfer-test", fileName, totalSize, chunkIndex, totalChunks,
                "0".repeat(64), new byte[dataSize]);
    }

    @Test
    @DisplayName("Rechaza chunks que superan el limite de 64 KB")
    public void testChunkTooLarge() {
        TransferException ex = assertThrows(TransferException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("grande.bin", 100_000, 0, 2, 65 * 1024)));

        assertTrue(ex.reason.contains("Tamano de chunk"), ex.reason);
    }

    @Test
    @DisplayName("Rechaza archivos que superan el limite total de 200 MB")
    public void testFileTooLarge() {
        TransferException ex = assertThrows(TransferException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("enorme.bin", FileRelayService.MAX_ALLOWED_FILE_SIZE + 1, 0, 1, 10)));

        assertTrue(ex.reason.contains("Tamano total"), ex.reason);
    }

    @Test
    @DisplayName("Rechaza nombres con rutas o path traversal")
    public void testUnsafeFileName() {
        TransferException ex = assertThrows(TransferException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("../../etc/passwd", 10, 0, 1, 10)));

        assertTrue(ex.reason.contains("Nombre de archivo invalido"), ex.reason);
    }

    @Test
    @DisplayName("Rechaza indices de chunk fuera del rango declarado")
    public void testChunkIndexOutOfRange() {
        TransferException ex = assertThrows(TransferException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("corto.bin", 10, 2, 2, 5)));

        assertTrue(ex.reason.contains("Indice de chunk invalido"), ex.reason);
    }

    @Test
    @DisplayName("Rechaza chunks cuya posicion excede el tamano total")
    public void testChunkOffsetExceedsTotalSize() {
        TransferException ex = assertThrows(TransferException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("corto.bin", 1000, 2, 3, 10)));

        assertTrue(ex.reason.contains("fuera de rango"), ex.reason);
    }

    @Test
    @DisplayName("Un chunk valido pasa las validaciones y busca al destinatario")
    public void testValidChunkReachesRecipientLookup() {
        assertThrows(UserNotFoundException.class,
                () -> relayService.sendChunkToUser("ana", "luis",
                        chunk("valido.bin", 10, 0, 1, 10)));
    }
}
