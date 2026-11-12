package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.util.CommonConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompAuthChannelInterceptorTest {

    private static final String DEV_A = "dev_123456789";
    private static final String DEV_B = "dev_987654321";
    private static final String DEV_C = "dev_abcdef123";

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(new ConnectionIdentityRegistry());
    }

    private Message<byte[]> frame(StompCommand command, String sessionId,
                                  Map<String, Object> attributes, Consumer<StompHeaderAccessor> customize) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(attributes);
        customize.accept(accessor);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Map<String, Object> connectAs(String sessionId, String deviceId) {
        Map<String, Object> attrs = new HashMap<>();
        interceptor.preSend(frame(StompCommand.CONNECT, sessionId, attrs, a -> {
            a.setNativeHeader(CommonConstants.HEADER_DEVICE_ID, deviceId);
            a.setNativeHeader(CommonConstants.HEADER_TOKEN, "tok-" + deviceId);
        }), null);
        return attrs;
    }

    @Test
    @DisplayName("CONNECT binds the device id; the session may then subscribe to its own topic")
    void connectBindsAndOwnSubscribeAllowed() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);

        assertEquals(DEV_A, attrs.get(CommonConstants.SESSION_DEVICE_ID));
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/" + DEV_A)), null));
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/webrtc/" + DEV_A)), null));
    }

    @Test
    @DisplayName("CONNECT without a deviceId header is rejected")
    void connectWithoutDeviceIdRejected() {
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.CONNECT, "s1", new HashMap<>(), a -> { }), null));
    }

    @Test
    @DisplayName("CONNECT claiming an id already held by a different token is rejected")
    void connectTakeoverRejected() {
        connectAs("s1", DEV_A);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.CONNECT, "s2", new HashMap<>(), a -> {
                    a.setNativeHeader(CommonConstants.HEADER_DEVICE_ID, DEV_A);
                    a.setNativeHeader(CommonConstants.HEADER_TOKEN, "attacker");
                }), null));
    }

    @Test
    @DisplayName("a session may NOT subscribe to another device's (well-formed) topic")
    void foreignSubscribeRejected() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/" + DEV_B)), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/room/" + DEV_C)), null));
    }

    @Test
    @DisplayName("a guarded topic whose device segment is not a well-formed device id is rejected")
    void malformedDeviceSegmentRejected() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);

        // Not "dev_" + hex, even though the session itself presented it (e.g. an old test id).
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/dev1")), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/webrtc/victim")), null));
    }

    @Test
    @DisplayName("non device-scoped destinations are not restricted")
    void unscopedDestinationAllowed() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(CommonConstants.SESSION_DEVICE_ID, DEV_A);

        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/public/news")), null));
    }

    @Test
    @DisplayName("wildcard (*) subscriptions are rejected")
    void wildcardSubscribeRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(CommonConstants.SESSION_DEVICE_ID, DEV_A);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/**")), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/*")), null));
    }

    @Test
    @DisplayName("question-mark (?) pattern subscriptions are rejected")
    void questionMarkPatternSubscribeRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(CommonConstants.SESSION_DEVICE_ID, DEV_A);

        // ? is single-character AntPathMatcher syntax: this would match every device's topic.
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/webrtc/dev_?????????")), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/dev_12345678?")), null));
    }

    @Test
    @DisplayName("brace ({var}) pattern subscriptions are rejected")
    void bracePatternSubscribeRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(CommonConstants.SESSION_DEVICE_ID, DEV_A);

        // {var} is AntPathMatcher template syntax and would let one subscription span devices.
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/webrtc/dev_{room}")), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/{type}/" + DEV_A)), null));
    }

    @Test
    @DisplayName("direct sends to /topic are rejected")
    void directSendToTopicRejected() {
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SEND, "s1", new HashMap<>(), a -> a.setDestination("/topic/webrtc/victim")), null));
    }

    @Test
    @DisplayName("sends to application destinations (/app) are allowed")
    void appSendsAllowed() {
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SEND, "s1", new HashMap<>(), a -> a.setDestination("/app/webrtc/offer")), null));
    }

    @Test
    @DisplayName("SUBSCRIBE is rejected once a newer session claimed the same id (same token)")
    void supersededSessionSubscribeRejected() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);
        // A second session re-claims the same id with the same token (e.g. a page refresh while the
        // old tab lingers) and becomes the owner.
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.CONNECT, "s2", new HashMap<>(), a -> {
                    a.setNativeHeader(CommonConstants.HEADER_DEVICE_ID, DEV_A);
                    a.setNativeHeader(CommonConstants.HEADER_TOKEN, "tok-" + DEV_A);
                }), null));

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/" + DEV_A)), null),
                "the superseded session must not open new subscriptions to the id");
    }

    @Test
    @DisplayName("SUBSCRIBE is rejected once the session's claim expired")
    void expiredClaimSubscribeRejected() {
        ConnectionIdentityRegistry expiredRegistry = mock(ConnectionIdentityRegistry.class);
        when(expiredRegistry.claim("s1", DEV_A, "tok-" + DEV_A)).thenReturn(true);
        // The CONNECT-time claim has lapsed (evicted lazily on the ownership lookup).
        when(expiredRegistry.getOwnerSessionId(DEV_A)).thenReturn(null);
        StompAuthChannelInterceptor expiredInterceptor = new StompAuthChannelInterceptor(expiredRegistry);

        Map<String, Object> attrs = new HashMap<>();
        assertDoesNotThrow(() -> expiredInterceptor.preSend(
                frame(StompCommand.CONNECT, "s1", attrs, a -> {
                    a.setNativeHeader(CommonConstants.HEADER_DEVICE_ID, DEV_A);
                    a.setNativeHeader(CommonConstants.HEADER_TOKEN, "tok-" + DEV_A);
                }), null));

        assertThrows(MessagingException.class, () -> expiredInterceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", attrs, a -> a.setDestination("/topic/devices/" + DEV_A)), null),
                "a session whose claim expired must not subscribe to the device's topics");
    }

    @Test
    @DisplayName("SEND is rejected once a newer session claimed the same id")
    void supersededSessionSendRejected() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.CONNECT, "s2", new HashMap<>(), a -> {
                    a.setNativeHeader(CommonConstants.HEADER_DEVICE_ID, DEV_A);
                    a.setNativeHeader(CommonConstants.HEADER_TOKEN, "tok-" + DEV_A);
                }), null));

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                frame(StompCommand.SEND, "s1", attrs, a -> a.setDestination("/app/rooms/create")), null),
                "the superseded session must not send application frames under the id");
    }

    @Test
    @DisplayName("SEND is allowed while the session still owns its id")
    void ownerSendAllowed() {
        Map<String, Object> attrs = connectAs("s1", DEV_A);

        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SEND, "s1", attrs, a -> a.setDestination("/app/rooms/create")), null));
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SEND, "s1", attrs, a -> a.setDestination("/app/register")), null));
    }
}
