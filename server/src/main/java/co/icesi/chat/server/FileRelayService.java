package co.icesi.chat.server;

import co.icesi.Chat.FileChunk;
import co.icesi.Chat.NotInRoomException;
import co.icesi.Chat.RoomNotFoundException;
import co.icesi.Chat.TransferException;
import co.icesi.Chat.UserNotFoundException;
import co.icesi.chat.common.AudioConstants;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servicio de retransmision de fragmentos multimedia sobre ZeroC Ice.
 * El servidor NO almacena el archivo completo en disco ni en memoria: reenvia los chunks
 * en tiempo real hacia el usuario o sala destino.
 */
public class FileRelayService {

    private static final Logger LOGGER = Logger.getLogger(FileRelayService.class.getName());
    private static final int MAX_ALLOWED_CHUNK_SIZE = 64 * 1024; // 64 KB
    static final long MAX_ALLOWED_FILE_SIZE = 200L * 1024 * 1024; // 200 MB
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final UserRegistry userRegistry;
    private final RoomManager roomManager;
    private ClientReaper clientReaper;

    public FileRelayService(UserRegistry userRegistry, RoomManager roomManager) {
        this.userRegistry = userRegistry;
        this.roomManager = roomManager;
    }

    public void setClientReaper(ClientReaper clientReaper) {
        this.clientReaper = clientReaper;
    }

    public void sendChunkToUser(String from, String to, FileChunk chunk)
            throws UserNotFoundException, TransferException {
        validateChunk(chunk);

        UserSession target = userRegistry.get(to);
        if (target == null) {
            UserNotFoundException ex = new UserNotFoundException();
            ex.reason = "Destinatario '" + to + "' no encontrado para transferencia de archivo";
            throw ex;
        }

        if (target.getCallback() != null) {
            target.getCallback().receiveFileChunkAsync(from, "", chunk).whenComplete((res, ex) -> {
                if (ex != null) {
                    LOGGER.log(Level.WARNING, "Error al entregar chunk a '" + to + "': " + ex.getMessage());
                    if (clientReaper != null) {
                        clientReaper.dropUser(to, "Fallo en entrega de chunk multimedia");
                    }
                }
            });
        }
    }

    public void sendChunkToRoom(String from, String room, FileChunk chunk)
            throws RoomNotFoundException, NotInRoomException, TransferException {
        validateChunk(chunk);

        if (!roomManager.listRooms().contains(room)) {
            RoomNotFoundException ex = new RoomNotFoundException();
            ex.reason = "La sala '" + room + "' no existe";
            throw ex;
        }

        if (!roomManager.isMember(room, from)) {
            NotInRoomException ex = new NotInRoomException();
            ex.reason = "No perteneces a la sala '" + room + "' para transferir archivos";
            throw ex;
        }

        Collection<String> members = roomManager.getMembers(room);
        for (String member : members) {
            // Regla: no reenviar chunk al emisor
            if (member.equals(from)) {
                continue;
            }
            UserSession s = userRegistry.get(member);
            if (s != null && s.getCallback() != null) {
                s.getCallback().receiveFileChunkAsync(from, room, chunk).whenComplete((res, ex) -> {
                    if (ex != null) {
                        LOGGER.log(Level.WARNING, "Error al entregar chunk a '" + member + "' en sala '" + room + "'");
                        if (clientReaper != null) {
                            clientReaper.dropUser(member, "Fallo en entrega de chunk en sala");
                        }
                    }
                });
            }
        }
    }

    private void validateChunk(FileChunk chunk) throws TransferException {
        if (chunk == null || chunk.data == null) {
            throw transferError("Chunk de archivo nulo o sin datos");
        }

        if (isUnsafeFileName(chunk.fileName)) {
            throw transferError("Nombre de archivo invalido: solo se permite el nombre base sin rutas");
        }

        if (chunk.totalSize < 0 || chunk.totalSize > MAX_ALLOWED_FILE_SIZE) {
            throw transferError("Tamano total de archivo (" + chunk.totalSize
                    + " bytes) excede el limite maximo permitido de " + MAX_ALLOWED_FILE_SIZE);
        }

        if (chunk.data.length > MAX_ALLOWED_CHUNK_SIZE) {
            throw transferError("Tamano de chunk (" + chunk.data.length
                    + " bytes) excede el limite maximo permitido de " + MAX_ALLOWED_CHUNK_SIZE);
        }

        if (chunk.totalChunks <= 0 || chunk.chunkIndex < 0 || chunk.chunkIndex >= chunk.totalChunks) {
            throw transferError("Indice de chunk invalido: " + chunk.chunkIndex + " de " + chunk.totalChunks);
        }

        long offset = (long) chunk.chunkIndex * AudioConstants.CHUNK_SIZE;
        if (offset + chunk.data.length > chunk.totalSize) {
            throw transferError("Chunk fuera de rango: indice " + chunk.chunkIndex
                    + " excede el tamano declarado de " + chunk.totalSize + " bytes");
        }
    }

    private boolean isUnsafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_LENGTH) {
            return true;
        }
        return fileName.equals(".") || fileName.equals("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0;
    }

    private TransferException transferError(String reason) {
        TransferException ex = new TransferException();
        ex.reason = reason;
        return ex;
    }
}
