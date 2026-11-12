package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.util.CommonConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketConfigTest {

    @LocalServerPort
    private int port;

    @Test
    void testWebSocketConnection() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        ));

        String url = "ws://localhost:" + port + "/ws";

        // A device id is required on CONNECT now that the auth interceptor binds it to the session.
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(CommonConstants.HEADER_DEVICE_ID, "dev_configtest");
        connectHeaders.add(CommonConstants.HEADER_TOKEN, "tok_configtest");

        StompSession stompSession = stompClient
                .connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        try {
            assertTrue(stompSession.isConnected(), "A WebSocket connection must be established.");
        } finally {
            stompSession.disconnect();
        }
    }

    @Test
    void configureWebSocketTransportAppliesMessageAndSendLimits() {
        WebSocketConfig config = new WebSocketConfig(
                new StompAuthChannelInterceptor(new ConnectionIdentityRegistry()),
                new StompTopicDeliveryGuard(new ConnectionIdentityRegistry()),
                "");

        ExposingTransportRegistration registration = new ExposingTransportRegistration();
        config.configureWebSocketTransport(registration);

        // These feed the SubProtocolWebSocketHandler (send limits) and the StompSubProtocolHandler
        // (message size) — the properties removed from application.properties were never consumed.
        assertEquals(1024 * 1024, registration.getMessageSizeLimit(), "STOMP text/binary messages capped at 1 MB");
        assertEquals(512 * 1024, registration.getSendBufferSizeLimit(), "outbound buffer capped at 512 KB");
        assertEquals(10_000, registration.getSendTimeLimit(), "a slow consumer may pin the queue for at most 10 s");
    }

    /** Exposes the protected registration getters so the configured limits can be asserted. */
    private static class ExposingTransportRegistration extends WebSocketTransportRegistration {
        @Override public Integer getMessageSizeLimit() { return super.getMessageSizeLimit(); }
        @Override public Integer getSendBufferSizeLimit() { return super.getSendBufferSizeLimit(); }
        @Override public Integer getSendTimeLimit() { return super.getSendTimeLimit(); }
    }
}
