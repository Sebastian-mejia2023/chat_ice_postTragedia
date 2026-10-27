package co.icesi.chat.client.file;

import co.icesi.Chat.FileChunk;
import co.icesi.chat.client.ui.ConsolePrinter;
import co.icesi.chat.client.util.ErrorMessages;
import co.icesi.chat.common.AudioConstants;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receptor y ensamblador de fragmentos multimedia sobre ZeroC Ice.
 * Soporta chunks desordenados mediante RandomAccessFile y valida integridad via SHA-256.
 */
public class FileReceiver {

    static final long INCOMPLETE_TRANSFER_TIMEOUT_MS = 120_000L;

    private final File downloadDir;
    private final ConsolePrinter printer;

    private static class ActiveTransfer {
        final String transferId;
        final String safeFileName;
        final File targetFile;
        final long totalSize;
        final int totalChunks;
        final String expectedSha256;
        final RandomAccessFile raf;
        final Set<Integer> receivedChunks = ConcurrentHashMap.newKeySet();
        final AtomicBoolean completed = new AtomicBoolean(false);
        volatile long lastActivityMs;

        ActiveTransfer(String transferId, String safeFileName, File targetFile,
                       long totalSize, int totalChunks, String expectedSha256,
                       RandomAccessFile raf) {
            this.transferId = transferId;
            this.safeFileName = safeFileName;
            this.targetFile = targetFile;
            this.totalSize = totalSize;
            this.totalChunks = totalChunks;
            this.expectedSha256 = expectedSha256;
            this.raf = raf;
            this.lastActivityMs = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, ActiveTransfer> transfers = new ConcurrentHashMap<>();

    public FileReceiver(ConsolePrinter printer) {
        this(new File("downloads"), printer);
    }

    public FileReceiver(File downloadDir, ConsolePrinter printer) {
        this.downloadDir = downloadDir;
        this.printer = printer;
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
    }

    public void onChunkReceived(String from, String room, FileChunk chunk) {
        try {
            sweepStaleTransfers(System.currentTimeMillis());

            ActiveTransfer transfer = transfers.computeIfAbsent(chunk.transferId, id -> {
                try {
                    // Sanitizacion estricta contra Path Traversal
                    String baseName = Paths.get(chunk.fileName).getFileName().toString();
                    baseName = baseName.replace("..", "").replace("/", "").replace("\\", "");
                    if (baseName.isEmpty()) {
                        baseName = "descarga_" + System.currentTimeMillis();
                    }

                    File dest = new File(downloadDir, baseName);
                    // Evitar sobreescritura accidental agregando sufijo numerico
                    int counter = 1;
                    while (dest.exists()) {
                        String nameWithoutExt = baseName;
                        String ext = "";
                        int dot = baseName.lastIndexOf('.');
                        if (dot > 0) {
                            nameWithoutExt = baseName.substring(0, dot);
                            ext = baseName.substring(dot);
                        }
                        dest = new File(downloadDir, nameWithoutExt + "_" + counter + ext);
                        counter++;
                    }

                    RandomAccessFile raf = new RandomAccessFile(dest, "rw");
                    raf.setLength(chunk.totalSize);

                    String origin = room != null && !room.isEmpty() ? "sala #" + room : "usuario " + from;
                    printer.printFile("Recibiendo archivo '" + dest.getName() + "' (" + chunk.totalSize + " bytes) de " + origin);

                    return new ActiveTransfer(id, dest.getName(), dest, chunk.totalSize, chunk.totalChunks, chunk.sha256, raf);
                } catch (Exception e) {
                    printer.printError("Error inicializando recepcion de archivo: " + ErrorMessages.describe(e));
                    return null;
                }
            });

            if (transfer == null) {
                return;
            }

            if (chunk.chunkIndex < 0 || chunk.chunkIndex >= transfer.totalChunks) {
                printer.printError("Chunk fuera de rango para '" + transfer.safeFileName + "': "
                        + chunk.chunkIndex + " de " + transfer.totalChunks);
                return;
            }

            long offset = (long) chunk.chunkIndex * AudioConstants.CHUNK_SIZE;
            if (offset + chunk.data.length > transfer.totalSize) {
                printer.printError("Chunk excede el tamano declarado para '" + transfer.safeFileName + "'");
                return;
            }

            synchronized (transfer.raf) {
                transfer.raf.seek(offset);
                transfer.raf.write(chunk.data);
            }
            transfer.lastActivityMs = System.currentTimeMillis();

            boolean firstTime = transfer.receivedChunks.add(chunk.chunkIndex);
            if (!firstTime) {
                // Un chunk duplicado no debe contar dos veces ni completar la transferencia antes de tiempo.
                return;
            }

            if (transfer.receivedChunks.size() >= transfer.totalChunks
                    && transfer.completed.compareAndSet(false, true)) {
                completeTransfer(chunk.transferId, transfer);
            }
        } catch (Exception e) {
            printer.printError("Error al procesar chunk de archivo: " + ErrorMessages.describe(e));
        }
    }

    void sweepStaleTransfers(long now) {
        for (Map.Entry<String, ActiveTransfer> entry : transfers.entrySet()) {
            ActiveTransfer transfer = entry.getValue();
            if (now - transfer.lastActivityMs <= INCOMPLETE_TRANSFER_TIMEOUT_MS) {
                continue;
            }
            if (!transfers.remove(entry.getKey(), transfer)) {
                continue;
            }
            synchronized (transfer.raf) {
                try {
                    transfer.raf.close();
                } catch (Exception ignored) {
                }
            }
            if (transfer.targetFile.exists() && !transfer.targetFile.delete()) {
                printer.printError("No se pudo eliminar la transferencia incompleta: "
                        + transfer.targetFile.getAbsolutePath());
            } else {
                printer.printError("Transferencia incompleta descartada por inactividad: "
                        + transfer.safeFileName);
            }
        }
    }

    private void completeTransfer(String transferId, ActiveTransfer transfer) throws Exception {
        transfers.remove(transferId, transfer);
        synchronized (transfer.raf) {
            transfer.raf.close();
        }

        String actualSha256 = FileSender.computeSha256(transfer.targetFile);
        if (actualSha256.equalsIgnoreCase(transfer.expectedSha256)) {
            printer.printFile("Archivo completado exitosamente: " + transfer.targetFile.getAbsolutePath() + " [SHA-256 VERIFICADO]");
        } else {
            printer.printError("Corrupcion detectada en archivo recibido '" + transfer.safeFileName + "'. Hash SHA-256 no coincide.");
            if (transfer.targetFile.exists() && !transfer.targetFile.delete()) {
                printer.printError("No se pudo eliminar el archivo corrupto: " + transfer.targetFile.getAbsolutePath());
            }
        }
    }
}
