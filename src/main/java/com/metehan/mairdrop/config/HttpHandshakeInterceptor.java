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
import java.util.Map;

public class HttpHandshakeInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(HttpHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest servletRequest) {

            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String ip = getClientIp(httpRequest);

            String group = ip;
            if (ip.startsWith("192.168.") ||
                    ip.startsWith("10.") ||
                    ip.equals("127.0.0.1") ||
                    ip.equals("0:0:0:0:0:0:0:1")) {
                group = CommonConstants.LOCAL_NETWORK;
            }

            attributes.put(CommonConstants.NETWORK_GROUP, group);
            log.info("Handshake IP: {} -> Group: {}", ip, group);
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader(CommonConstants.X_FORWARDED_FOR);
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        return ip.split(",")[0].trim();
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {
    }
}