package co.icesi.chat.client.file;

import co.icesi.Chat.ChatServicePrx;
import co.icesi.Chat.FileChunk;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.client.util.ErrorMessages;
import co.icesi.chat.common.AudioConstants;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Emisor asincrono de archivos multimedia sobre ZeroC Ice mediante fragmentacion (Chunking).
 */
public class FileSender {

    private final ChatServicePrx chatService;
    private final ConsolePrinter printer;
    private final ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FileSender-Worker");
        t.setDaemon(true);
        return t;
    });

    public FileSender(ChatServicePrx chatService, ConsolePrinter printer) {
        this.chatService = chatService;
        this.printer = printer;
    }

    public void sendFileAsync(String from, String destination, String filePath, boolean isRoom) {
        threadPool.submit(() -> {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                printer.printError("Archivo no encontrado o invalido: " + filePath);
                return;
            }

            long totalSize = file.length();
            String fileName = file.getName();
            int totalChunks = (int) Math.ceil((double) totalSize / AudioConstants.CHUNK_SIZE);
            if (totalChunks == 0) {
                totalChunks = 1;
            }

            printer.printFile("Iniciando envio de '" + fileName + "' (" + totalSize + " bytes en " + totalChunks + " fragmentos) a " + destination);

            try {
                // 1. Calcular hash SHA-256 del archivo completo
                String sha256 = computeSha256(file);
                String transferId = UUID.randomUUID().toString();

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[AudioConstants.CHUNK_SIZE];
                    int chunkIndex = 0;
                    int bytesRead;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);

                        FileChunk chunk = new FileChunk(
                                transferId,
                                fileName,
                                totalSize,
                                chunkIndex,
                                totalChunks,
                                sha256,
                                data
                        );

                        if (isRoom) {
                            chatService.sendFileChunkToRoom(from, destination, chunk);
                        } else {
                            chatService.sendFileChunkToUser(from, destination, chunk);
                        }

                        chunkIndex++;
                    }

                    // Manejo especial de archivos de 0 bytes
                    if (totalSize == 0) {
                        FileChunk emptyChunk = new FileChunk(
                                transferId, fileName, 0, 0, 1, sha256, new byte[0]
                        );
                        if (isRoom) {
                            chatService.sendFileChunkToRoom(from, destination, emptyChunk);
                        } else {
                            chatService.sendFileChunkToUser(from, destination, emptyChunk);
                        }
                    }
                }

                printer.printFile("Archivo '" + fileName + "' enviado exitosamente a " + destination);
            } catch (Exception e) {
                printer.printError("Error enviando archivo '" + fileName + "': " + ErrorMessages.describe(e));
            }
        });
    }

    public static String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
