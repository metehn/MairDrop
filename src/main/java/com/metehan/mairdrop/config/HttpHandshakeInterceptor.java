package com.metehan.mairdrop.config;

import com.metehan.mairdrop.util.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HttpHandshakeInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(HttpHandshakeInterceptor.class);

    private final Set<String> trustedProxyIps;

    /**
     * Default constructor: no proxy is trusted, so {@code X-Forwarded-For} is never consulted and
     * the direct peer address decides the network group.
     */
    public HttpHandshakeInterceptor() {
        this(Set.of());
    }

    public HttpHandshakeInterceptor(Set<String> trustedProxyIps) {
        this.trustedProxyIps = (trustedProxyIps == null) ? Set.of() : Set.copyOf(trustedProxyIps);
    }

    /**
     * Parses a comma-separated property value ({@code mairdrop.trusted-proxy-ips}) into the set of
     * peer addresses allowed to set {@code X-Forwarded-For}. Blanks are skipped and IPv4-mapped
     * IPv6 prefixes are stripped so {@code ::ffff:127.0.0.1} and {@code 127.0.0.1} compare equal.
     */
    public static Set<String> parseTrustedProxyIps(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> ips = new HashSet<>();
        for (String raw : csv.split(",")) {
            String ip = normalizeIp(raw.trim());
            if (!ip.isEmpty()) {
                ips.add(ip);
            }
        }
        return Set.copyOf(ips);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest servletRequest) {

            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            // Normalise the FINAL resolved address (the trusted XFF hop, or the direct peer) so a
            // dual-stack peer reported as ::ffff:192.168.1.5 and one reported as 192.168.1.5 land
            // in the same group and rate-limit key — otherwise one client could split its
            // rate-limit windows (or its network group) across the two spellings.
            String ip = normalizeIp(getClientIp(httpRequest));

            String group = isLocalNetwork(ip) ? CommonConstants.LOCAL_NETWORK : ip;

            attributes.put(CommonConstants.NETWORK_GROUP, group);
            attributes.put(CommonConstants.SESSION_CLIENT_IP, ip);
            log.info("Handshake IP: {} -> Group: {}", ip, group);
        }
        return true;
    }

    private boolean isLocalNetwork(String rawIp) {
        String ip = normalizeIp(rawIp);
        return ip.startsWith("192.168.")
                || ip.startsWith("10.")
                || (ip.startsWith("172.") && isPrivate172(ip))
                || ip.equals("127.0.0.1")
                || ip.startsWith("127.")
                || ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("fe80:");
    }

    // Strip the IPv4-mapped IPv6 prefix so dual-stack peers reported as ::ffff:192.168.1.5 still
    // match the plain "192.168." style private-range checks.
    private static String normalizeIp(String ip) {
        if (ip == null) {
            return "";
        }
        if (ip.startsWith("::ffff:") && ip.indexOf('.') > 0) {
            return ip.substring("::ffff:".length());
        }
        return ip;
    }

    // RFC 1918: 172.16.0.0/12 covers 172.16.x.x through 172.31.x.x
    private boolean isPrivate172(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // Only trust X-Forwarded-For when the direct peer is an explicitly configured trusted proxy.
        // Trusting every private/loopback peer is not enough: in a Docker-published deployment every
        // client's remoteAddr is the 172.x bridge, so the header would always be trusted and a client
        // could spoof it to rotate its rate-limit key or land itself in the LOCAL_NETWORK group.
        if (trustedProxyIps.contains(normalizeIp(remoteAddr))) {
            String forwarded = request.getHeader(CommonConstants.X_FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                // The rightmost entry is the address the trusted proxy actually observed; entries to
                // its left are attacker-controllable and must be ignored. Scan from the right for the
                // first non-blank hop so a degenerate header (e.g. "," or a trailing comma) neither
                // throws nor yields an empty group; fall back to the direct peer if every hop is blank.
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    if (!hop.isEmpty()) {
                        return hop;
                    }
                }
            }
        }
        return remoteAddr != null ? remoteAddr.trim() : "";
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {
    }
}
