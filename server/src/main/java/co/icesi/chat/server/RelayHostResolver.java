package co.icesi.chat.server;

/**
 * Decide que IP debe anunciar el servidor para el AudioRelay UDP.
 * El servidor suele escuchar en 0.0.0.0, pero esa direccion no es valida para que un cliente
 * remoto envie audio: se debe anunciar la IP local real de la conexion Ice o una IP configurada.
 */
final class RelayHostResolver {

    static final String LOOPBACK = "127.0.0.1";

    private RelayHostResolver() {
    }

    static String resolve(String advertisedHost, String connectionLocalAddress, String configuredHost) {
        String explicit = usableHost(advertisedHost);
        if (explicit != null) {
            return explicit;
        }

        String observed = usableHost(connectionLocalAddress);
        if (observed != null) {
            return observed;
        }

        String configured = usableHost(configuredHost);
        if (configured != null) {
            return configured;
        }

        return LOOPBACK;
    }

    static String usableHost(String host) {
        if (host == null) {
            return null;
        }

        String candidate = host.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }

        if (candidate.isEmpty()
                || "0.0.0.0".equals(candidate)
                || "::".equals(candidate)
                || "0:0:0:0:0:0:0:0".equals(candidate)
                || "*".equals(candidate)) {
            return null;
        }

        return candidate;
    }
}
