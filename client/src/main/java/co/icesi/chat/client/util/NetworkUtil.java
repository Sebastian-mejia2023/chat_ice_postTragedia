package co.icesi.chat.client.util;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Utilidades para deteccion de direcciones IP de red local (LAN) evitando localhost.
 */
public final class NetworkUtil {

    private NetworkUtil() {
        // Prevenir instanciacion
    }

    public static String getLocalLanIp(String serverHost) {
        String prop = System.getProperty("client.host");
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim();
        }

        // Intento 1: conectar un socket UDP efimero hacia el servidor para deducir la interfaz real
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress target = InetAddress.getByName(serverHost != null ? serverHost : "8.8.8.8");
            socket.connect(target, 10000);
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
        }

        // Intento 2: escanear interfaces de red de la maquina
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "127.0.0.1";
    }
}
