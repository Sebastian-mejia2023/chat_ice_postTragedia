package co.icesi.chat.client.util;

import co.icesi.Chat.CallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ErrorMessagesTest {

    @Test
    @DisplayName("Usa el campo reason de las excepciones de negocio Slice")
    public void testSliceExceptionReason() {
        CallException exception = new CallException();
        exception.reason = "El usuario 'ana' se encuentra ocupado en otra llamada";

        assertEquals(exception.reason, ErrorMessages.describe(exception));
    }

    @Test
    @DisplayName("Usa el mensaje de las excepciones Java y busca en la causa anidada")
    public void testNestedCauseMessage() {
        RuntimeException wrapper = new RuntimeException(null, new IOException("archivo bloqueado"));

        assertEquals("archivo bloqueado", ErrorMessages.describe(wrapper));
    }

    @Test
    @DisplayName("Nunca devuelve null")
    public void testUnknownErrorHasFallback() {
        assertEquals("RuntimeException", ErrorMessages.describe(new RuntimeException()));
        assertEquals("error desconocido", ErrorMessages.describe(null));
    }
}
