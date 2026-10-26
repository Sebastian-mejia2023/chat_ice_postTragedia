package co.icesi.chat.client.util;

import co.icesi.Chat.ChatException;

/**
 * Convierte excepciones de Ice o de la JVM en mensajes comprensibles para el usuario.
 * Las excepciones de negocio generadas desde Slice no sobrescriben getMessage(): el texto
 * util viaja en el campo publico `reason`.
 */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "error desconocido";
        }

        Throwable current = error;
        while (current != null) {
            if (current instanceof ChatException) {
                String reason = ((ChatException) current).reason;
                if (reason != null && !reason.isBlank()) {
                    return reason;
                }
            }
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }
}
