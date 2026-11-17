package com.kapish.bridgeshare;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Finds the phone's local IPv4 address on Wi-Fi or hotspot interfaces. */
final class NetUtil {
    private NetUtil() {
    }

    static final class Addr {
        final String iface;
        final String ip;

        Addr(String iface, String ip) {
            this.iface = iface;
            this.ip = ip;
        }
    }

    static List<Addr> localAddresses() {
        List<Addr> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                // Mobile data interfaces can't be reached by the iPhone.
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")
                        || name.startsWith("tun") || name.startsWith("ppp")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        out.add(new Addr(name, a.getHostAddress()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Picks the best address. With our own hotspot, prefer the access-point interface. */
    static String pickAddress(boolean hotspot) {
        List<Addr> all = localAddresses();
        if (all.isEmpty()) return null;
        String[] prefer = hotspot
                ? new String[]{"swlan", "ap", "softap", "wlan1", "wlan2", "wlan"}
                : new String[]{"wlan0", "wlan", "swlan", "ap", "eth"};
        for (String p : prefer) {
            for (Addr a : all) {
                if (a.iface.startsWith(p)) return a.ip;
            }
        }
        return all.get(0).ip;
    }
}
