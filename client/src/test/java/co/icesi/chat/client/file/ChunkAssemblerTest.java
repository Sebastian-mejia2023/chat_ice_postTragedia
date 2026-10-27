package co.icesi.chat.client.file;

import co.icesi.Chat.FileChunk;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.common.AudioConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkAssemblerTest {

    @Test
    @DisplayName("Ensamblado de fragmentos multimedia desordenados con verificacion de integridad SHA-256")
    public void testReassemblyOutOfOrder(@TempDir Path tempDir) throws Exception {
        ConsolePrinter printer = new ConsolePrinter();
        FileReceiver receiver = new FileReceiver(tempDir.toFile(), printer);

        // Crear datos de prueba (100 KB = 3 chunks de 48 KB)
        int totalSize = 100 * 1024;
        byte[] original = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            original[i] = (byte) (i % 256);
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(original);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String expectedSha256 = hexString.toString();

        int chunkSize = AudioConstants.CHUNK_SIZE;
        int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);

        byte[] chunk0Data = new byte[chunkSize];
        System.arraycopy(original, 0, chunk0Data, 0, chunkSize);
        FileChunk chunk0 = new FileChunk("transfer-1", "test.dat", totalSize, 0, totalChunks, expectedSha256, chunk0Data);

        byte[] chunk1Data = new byte[chunkSize];
        System.arraycopy(original, chunkSize, chunk1Data, 0, chunkSize);
        FileChunk chunk1 = new FileChunk("transfer-1", "test.dat", totalSize, 1, totalChunks, expectedSha256, chunk1Data);

        int remaining = totalSize - (2 * chunkSize);
        byte[] chunk2Data = new byte[remaining];
        System.arraycopy(original, 2 * chunkSize, chunk2Data, 0, remaining);
        FileChunk chunk2 = new FileChunk("transfer-1", "test.dat", totalSize, 2, totalChunks, expectedSha256, chunk2Data);

        // Enviar desordenados: primero chunk 2, luego 0, finalmente 1
        receiver.onChunkReceived("alice", "", chunk2);
        receiver.onChunkReceived("alice", "", chunk0);
        receiver.onChunkReceived("alice", "", chunk1);

        File outputFile = new File(tempDir.toFile(), "test.dat");
        assertTrue(outputFile.exists(), "El archivo ensamblado debe existir");
        assertEquals(totalSize, outputFile.length(), "El tamano debe coincidir exactamente");

        byte[] reconstructed = Files.readAllBytes(outputFile.toPath());
        assertArrayEquals(original, reconstructed, "El contenido reconstruido debe ser byte por byte identico");
    }

    @Test
    @DisplayName("Proteccion contra vulnerabilidad Path Traversal en el nombre de archivo")
    public void testPathTraversalSanitization(@TempDir Path tempDir) throws Exception {
        ConsolePrinter printer = new ConsolePrinter();
        FileReceiver receiver = new FileReceiver(tempDir.toFile(), printer);

        // Contenido final de 10 bytes: [1, 2, 3] seguido de ceros (lo que no llega queda en cero).
        byte[] expectedContent = new byte[10];
        expectedContent[0] = 1;
        expectedContent[1] = 2;
        expectedContent[2] = 3;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder hashHex = new StringBuilder();
        for (byte b : md.digest(expectedContent)) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hashHex.append('0');
            hashHex.append(hex);
        }

        FileChunk maliciousChunk = new FileChunk(
                "transfer-evil",
                "../../../../etc/passwd",
                10,
                0,
                1,
                hashHex.toString(),
                new byte[]{1, 2, 3}
        );

        receiver.onChunkReceived("hacker", "", maliciousChunk);

        File maliciousOutput = new File(tempDir.toFile(), "passwd");
        assertTrue(maliciousOutput.exists(), "El archivo debe guardarse sanitizado dentro de la carpeta segura");

        File dangerousLocation = new File("/etc/passwd_evil");
        assertFalse(dangerousLocation.exists());
    }

    @Test
    @DisplayName("Los chunks duplicados no completan la transferencia antes de tiempo")
    public void testDuplicateChunkDoesNotCompleteEarly(@TempDir Path tempDir) throws Exception {
        ConsolePrinter printer = new ConsolePrinter();
        FileReceiver receiver = new FileReceiver(tempDir.toFile(), printer);

        int totalSize = 100 * 1024;
        byte[] original = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            original[i] = (byte) (i % 251);
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(original);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String sha256 = hexString.toString();

        int chunkSize = AudioConstants.CHUNK_SIZE;
        int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);

        FileChunk[] chunks = new FileChunk[totalChunks];
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * chunkSize;
            int length = Math.min(chunkSize, totalSize - offset);
            byte[] data = new byte[length];
            System.arraycopy(original, offset, data, 0, length);
            chunks[i] = new FileChunk("transfer-duplicado", "test.dat", totalSize, i, totalChunks, sha256, data);
        }

        // El chunk 0 llega dos veces: no debe contar como dos fragmentos distintos.
        receiver.onChunkReceived("alice", "", chunks[0]);
        receiver.onChunkReceived("alice", "", chunks[0]);
        receiver.onChunkReceived("alice", "", chunks[1]);
        receiver.onChunkReceived("alice", "", chunks[2]);

        File outputFile = new File(tempDir.toFile(), "test.dat");
        assertTrue(outputFile.exists());
        assertArrayEquals(original, Files.readAllBytes(outputFile.toPath()),
                "un chunk duplicado completo la transferencia antes de recibir todos los indices");
    }

    @Test
    @DisplayName("Las transferencias incompletas inactivas se descartan y eliminan")
    public void testStaleIncompleteTransferIsRemoved(@TempDir Path tempDir) throws Exception {
        ConsolePrinter printer = new ConsolePrinter();
        FileReceiver receiver = new FileReceiver(tempDir.toFile(), printer);

        int totalSize = 100 * 1024;
        FileChunk first = new FileChunk("transfer-incompleto", "parcial.bin", totalSize, 0, 3,
                "0".repeat(64), new byte[AudioConstants.CHUNK_SIZE]);

        receiver.onChunkReceived("alice", "", first);
        File partial = new File(tempDir.toFile(), "parcial.bin");
        assertTrue(partial.exists(), "El archivo parcial debe existir mientras la transferencia sigue activa");

        receiver.sweepStaleTransfers(System.currentTimeMillis() + FileReceiver.INCOMPLETE_TRANSFER_TIMEOUT_MS + 1000);
        assertFalse(partial.exists(), "La transferencia inactiva debe eliminarse");

        receiver.onChunkReceived("alice", "", first);
        assertTrue(partial.exists(), "Un chunk posterior debe iniciar una transferencia nueva");
    }
}
