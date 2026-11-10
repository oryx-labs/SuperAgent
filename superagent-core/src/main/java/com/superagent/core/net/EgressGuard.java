package com.superagent.core.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the server may connect on someone else's say-so (a link an agent reads, a sandbox's outbound traffic): http(s)
 * to public hosts. The host is resolved and every address it resolves to is checked, so a public name pointing at a
 * private address is refused too; callers following redirects check every hop. Loopback, private, link-local (cloud
 * metadata), carrier-grade NAT and unique-local ranges are refused unless an administrator allowed that host or range.
 *
 * <p>Residual gap: behind an HTTP proxy the proxy resolves the name again, so a host that changes its DNS answer in
 * between (rebinding) could still get through. Proxy configs should keep private ranges direct, which this refuses.
 */
public final class EgressGuard {

    /** Nothing internal allowed: for reading public links. */
    public static final EgressGuard PUBLIC_ONLY = new EgressGuard(List.of());

    private static final Set<Integer> PORTS = Set.of(-1, 80, 443, 8080, 8443);

    private final List<String> allowed;

    /**
     * {@code allowed}: internal hosts ({@code wiki.corp.example}, or {@code .corp.example} for its subdomains) and IPv4
     * ranges ({@code 10.8.0.0/16}) an administrator lets through.
     */
    public EgressGuard(Collection<String> allowed) {
        this.allowed = allowed.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    /** The address as a URI when it may be connected to; otherwise {@link EgressDeniedException} says why. */
    public URI check(String url) {
        URI uri = toUri(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new EgressDeniedException("只能读 http / https 链接");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressDeniedException("链接里没有域名");
        }
        if (!PORTS.contains(uri.getPort())) {
            throw new EgressDeniedException("不读这个端口上的地址");
        }
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (allowedHost(h)) {
            return uri;
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            throw internal();
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            throw new EgressDeniedException("找不到这个域名");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a) && !allowedAddress(a)) {
                throw internal();
            }
        }
        return uri;
    }

    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) != 0xfc; // fc00::/7, unique local
        }
        int x = b[0] & 0xff;
        int y = b[1] & 0xff;
        return !(x == 0 || x == 10 || x == 127 || x >= 224
                || (x == 100 && y >= 64 && y <= 127)      // carrier-grade NAT
                || (x == 169 && y == 254)                 // link-local, cloud metadata
                || (x == 172 && y >= 16 && y <= 31)
                || (x == 192 && y == 168)
                || (x == 198 && (y == 18 || y == 19)));   // benchmarking
    }

    /** A URI from an address as people paste it: spaces and non-ASCII characters (a Chinese path) get percent-encoded. */
    public static URI toUri(String raw) {
        String s = raw == null ? "" : raw.strip();
        StringBuilder sb = new StringBuilder();
        for (byte c : s.getBytes(StandardCharsets.UTF_8)) {
            int v = c & 0xff;
            if (v > 0x7e || v <= 0x20 || v == '"' || v == '<' || v == '>' || v == '\\' || v == '^' || v == '`'
                    || v == '{' || v == '|' || v == '}') {
                sb.append('%').append(String.format("%02X", v));
            } else {
                sb.append((char) v);
            }
        }
        try {
            return new URI(sb.toString());
        } catch (URISyntaxException e) {
            throw new EgressDeniedException("不是有效的网址");
        }
    }

    private boolean allowedHost(String host) {
        return allowed.stream().anyMatch(a -> !a.contains("/") && (a.equals(host) || (a.startsWith(".") && host.endsWith(a))));
    }

    private boolean allowedAddress(InetAddress address) {
        return address instanceof Inet4Address && allowed.stream().filter(a -> a.contains("/")).anyMatch(cidr -> inRange(address, cidr));
    }

    private static boolean inRange(InetAddress address, String cidr) {
        String[] p = cidr.split("/");
        try {
            int bits = Integer.parseInt(p[1]);
            byte[] net = InetAddress.getByName(p[0]).getAddress();
            byte[] ip = address.getAddress();
            if (net.length != ip.length || bits < 0 || bits > 32) {
                return false;
            }
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            return (toInt(net) & mask) == (toInt(ip) & mask);
        } catch (RuntimeException | UnknownHostException e) {
            return false; // a malformed entry allows nothing
        }
    }

    private static int toInt(byte[] b) {
        return (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
    }

    private static EgressDeniedException internal() {
        return new EgressDeniedException("这是内网或本机地址，不能读取");
    }
}
