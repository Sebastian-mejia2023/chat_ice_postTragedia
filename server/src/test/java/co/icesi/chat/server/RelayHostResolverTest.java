package co.icesi.chat.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayHostResolverTest {

    @Test
    @DisplayName("El host anunciado explicito tiene prioridad")
    void advertisedHostWins() {
        assertEquals("192.168.1.50",
                RelayHostResolver.resolve("192.168.1.50", "10.0.0.20", "0.0.0.0"));
    }

    @Test
    @DisplayName("Con bind 0.0.0.0 se usa la IP local de la conexion Ice")
    void connectionAddressIsUsedWhenConfiguredHostIsWildcard() {
        assertEquals("192.168.1.15",
                RelayHostResolver.resolve("", "192.168.1.15", "0.0.0.0"));
    }

    @Test
    @DisplayName("Un anuncio 0.0.0.0 no se propaga al cliente")
    void wildcardAdvertisedHostIsIgnored() {
        assertEquals("192.168.1.15",
                RelayHostResolver.resolve("0.0.0.0", "192.168.1.15", "0.0.0.0"));
    }

    @Test
    @DisplayName("Si la conexion no aporta IP se usa el host configurado")
    void configuredHostIsFallback() {
        assertEquals("192.168.1.80",
                RelayHostResolver.resolve("", "", "192.168.1.80"));
    }

    @Test
    @DisplayName("Sin ninguna IP util se usa loopback")
    void loopbackIsLastResort() {
        assertEquals(RelayHostResolver.LOOPBACK,
                RelayHostResolver.resolve(null, "::", "0.0.0.0"));
    }

    @Test
    @DisplayName("Se aceptan IPv6 entre corchetes y se eliminan espacios")
    void bracketedIpv6IsNormalized() {
        assertEquals("fe80::1",
                RelayHostResolver.resolve(" [fe80::1] ", null, null));
    }
}
