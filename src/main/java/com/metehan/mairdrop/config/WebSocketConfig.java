package com.metehan.mairdrop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // STOMP heartbeat: server <-> client ping every 10s
    // Detects half-open TCP connections fast and triggers SessionDisconnectEvent
    // instead of waiting for OS-level TCP keepalive (which can take many minutes).
    private static final long HEARTBEAT_INTERVAL_MS = 10000L;

    // Transport limits enforced by SubProtocolWebSocketHandler/StompSubProtocolHandler (see
    // configureWebSocketTransport below). The frontend caps the file count per offer batch so the
    // offer metadata (file names/sizes/hashes) always stays well below the 1 MB text limit.
    private static final int MAX_TEXT_MESSAGE_SIZE = 1024 * 1024;      // 1 MB
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;      // 512 KB
    private static final int SEND_TIME_LIMIT_MS = 10_000;             // 10 s

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompTopicDeliveryGuard topicDeliveryGuard;
    private final HttpHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                           StompTopicDeliveryGuard topicDeliveryGuard,
                           @Value("${mairdrop.trusted-proxy-ips:}") String trustedProxyIps) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.topicDeliveryGuard = topicDeliveryGuard;
        // X-Forwarded-For is only honored from explicitly allow-listed proxies; empty = never trust.
        this.handshakeInterceptor =
                new HttpHandshakeInterceptor(HttpHandshakeInterceptor.parseTrustedProxyIps(trustedProxyIps));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authorises CONNECT (device-id ownership) and SUBSCRIBE/SEND (own-topic / own-id only).
        registration.interceptors(stompAuthChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // Stops stale subscriptions (a session superseded by a newer claim of the same device id)
        // from receiving that device's private topics after a takeover. The channel keeps its
        // default executor; only the interceptor is added.
        registration.interceptors(topicDeliveryGuard);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // These set the SubProtocolWebSocketHandler transport limits: messageSizeLimit is the
        // text/binary STOMP message size the StompSubProtocolHandler accepts (Spring's default is
        // 64 KB, which multi-file signalling payloads can exceed), while the send buffer and time
        // limits bound how long a slow consumer may pin the outbound queue before its session is
        // closed. See MAX_TEXT_MESSAGE_SIZE/SEND_BUFFER_SIZE_LIMIT/SEND_TIME_LIMIT_MS above.
        registration.setMessageSizeLimit(MAX_TEXT_MESSAGE_SIZE);
        registration.setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT);
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
                .setTaskScheduler(stompHeartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Avoids a race where /app/rooms/join is processed before its preceding /app/register completes.
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(handshakeInterceptor)
                .withSockJS();
    }

    @Bean
    public TaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
