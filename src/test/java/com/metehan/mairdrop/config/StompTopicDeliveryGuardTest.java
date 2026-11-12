package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompTopicDeliveryGuardTest {

    private static final String DEV_A = "dev_123456789";

    private ConnectionIdentityRegistry registry;
    private StompTopicDeliveryGuard guard;

    @BeforeEach
    void setUp() {
        registry = new ConnectionIdentityRegistry();
        guard = new StompTopicDeliveryGuard(registry);
    }

    private Message<byte[]> delivery(String sessionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("a delivery to the owning session passes untouched")
    void deliveringToOwnerPasses() {
        registry.claim("owner-sess", DEV_A, "tok");
        Message<byte[]> message = delivery("owner-sess", "/topic/devices/" + DEV_A);

        assertSame(message, guard.preSend(message, null));
    }

    @Test
    @DisplayName("a delivery to a superseded (non-owning) session is dropped")
    void deliveringToSupersededSessionIsDropped() {
        registry.claim("owner-sess", DEV_A, "tok");
        registry.claim("new-owner-sess", DEV_A, "tok"); // newest claimant takes ownership

        assertNull(guard.preSend(delivery("owner-sess", "/topic/devices/" + DEV_A), null),
                "the superseded session must not receive the device's topics");

        Message<byte[]> ownersDelivery = delivery("new-owner-sess", "/topic/devices/" + DEV_A);
        assertSame(ownersDelivery, guard.preSend(ownersDelivery, null),
                "the current owner keeps receiving");
    }

    @Test
    @DisplayName("a delivery to a session while nobody owns the id is dropped")
    void deliveringToUnownedSessionIsDropped() {
        assertNull(guard.preSend(delivery("some-sess", "/topic/devices/" + DEV_A), null));
    }

    @Test
    @DisplayName("a delivery for an expired claim is dropped")
    void deliveringAfterClaimExpiredIsDropped() {
        ConnectionIdentityRegistry expiredRegistry = mock(ConnectionIdentityRegistry.class);
        when(expiredRegistry.getOwnerSessionId(DEV_A)).thenReturn(null); // evicted on lookup
        StompTopicDeliveryGuard expiredGuard = new StompTopicDeliveryGuard(expiredRegistry);

        assertNull(expiredGuard.preSend(delivery("some-sess", "/topic/devices/" + DEV_A), null));
    }

    @Test
    @DisplayName("server-originated messages without a session id pass untouched")
    void serverOriginatedMessagesPass() {
        Message<byte[]> message = delivery(null, "/topic/devices/" + DEV_A);

        assertSame(message, guard.preSend(message, null));
    }

    @Test
    @DisplayName("non device-scoped destinations pass untouched")
    void unscopedDestinationsPass() {
        registry.claim("owner-sess", DEV_A, "tok");

        Message<byte[]> message = delivery("other-sess", "/topic/public/news");
        assertSame(message, guard.preSend(message, null));
    }

    @Test
    @DisplayName("a malformed device segment is not guarded (cannot be a live subscription)")
    void malformedDeviceSegmentPasses() {
        registry.claim("owner-sess", DEV_A, "tok");

        Message<byte[]> message = delivery("other-sess", "/topic/devices/dev1");
        assertSame(message, guard.preSend(message, null));
    }

    @Test
    @DisplayName("deliveries without a destination pass untouched")
    void noDestinationPasses() {
        registry.claim("owner-sess", DEV_A, "tok");

        Message<byte[]> message = delivery("other-sess", null);
        assertSame(message, guard.preSend(message, null));
    }

    @Test
    @DisplayName("other guarded topics (webrtc/room/visibility) are enforced the same way")
    void allScopedTopicsAreGuarded() {
        registry.claim("owner-sess", DEV_A, "tok");
        registry.claim("new-owner-sess", DEV_A, "tok");

        for (String type : new String[]{"webrtc", "room", "visibility"}) {
            assertNull(guard.preSend(delivery("owner-sess", "/topic/" + type + "/" + DEV_A), null),
                    "superseded session must not receive /topic/" + type + "/{id}");
        }
    }

    @Test
    @DisplayName("the native destination header is used when no simulator destination is present")
    void nativeDestinationHeaderIsGuarded() {
        registry.claim("owner-sess", DEV_A, "tok");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId("other-sess");
        accessor.setNativeHeader(StompHeaderAccessor.STOMP_DESTINATION_HEADER, "/topic/devices/" + DEV_A);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertNull(guard.preSend(message, null));
    }

    @Test
    @DisplayName("message payload of a passing delivery is preserved")
    void passingDeliveryKeepsPayload() {
        registry.claim("owner-sess", DEV_A, "tok");
        String payload = "{\"type\":\"ROOM_CREATED\"}";
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId("owner-sess");
        accessor.setDestination("/topic/room/" + DEV_A);
        Message<String> message = MessageBuilder.createMessage(payload, accessor.getMessageHeaders());

        Message<?> result = guard.preSend(message, null);
        assertSame(message, result);
    }
}
