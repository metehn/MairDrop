package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.util.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Stops stale subscriptions from receiving a device's private topics after its id is taken over.
 *
 * <p>Subscriptions are permanent: once accepted, the SimpleBroker keeps delivering {@code
 * /topic/{devices|webrtc|room|visibility}/{deviceId}} frames to the subscribed session even after
 * another session claims the same id (same token, e.g. a page refresh while the old tab lingers).
 * The inbound SUBSCRIBE guard cannot retract them, so this interceptor sits on the client OUTBOUND
 * channel and drops every per-recipient delivery whose session is no longer the registry's current
 * owner of the addressed device id.
 *
 * <p>The SimpleBroker resolves app-published broadcasts ({@code SimpMessagingTemplate}) into one
 * message per matching subscription and stamps each with the recipient's session id; those are the
 * messages this guard checks. Messages without a session id are not per-client deliveries (e.g. the
 * original server-originated broadcast) and always pass untouched, as do non device-scoped
 * destinations.
 */
@Component
public class StompTopicDeliveryGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompTopicDeliveryGuard.class);
    private static final Set<String> DEVICE_SCOPED_TOPICS = Set.of("devices", "webrtc", "room", "visibility");

    private final ConnectionIdentityRegistry identityRegistry;

    public StompTopicDeliveryGuard(ConnectionIdentityRegistry identityRegistry) {
        this.identityRegistry = identityRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            // Server-originated messages (no recipient session yet) must pass untouched.
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            // Fall back to the STOMP native header (set when the broker re-writes the frame).
            destination = accessor.getFirstNativeHeader(StompHeaderAccessor.STOMP_DESTINATION_HEADER);
        }
        if (destination == null) {
            return message;
        }
        String[] parts = destination.split("/");
        // Only device-scoped topics ("/topic/{type}/{deviceId}") are guarded; anything else passes.
        if (parts.length < 4 || !"topic".equals(parts[1]) || !DEVICE_SCOPED_TOPICS.contains(parts[2])) {
            return message;
        }
        String target = parts[3];
        // A malformed device segment cannot be addressed by any subscription (the inbound guard
        // rejects both patterns and malformed ids), so there is no ownership to enforce: pass.
        if (!CommonConstants.DEVICE_ID_PATTERN.matcher(target).matches()) {
            return message;
        }
        String owner = identityRegistry.getOwnerSessionId(target);
        if (!sessionId.equals(owner)) {
            log.debug("Dropping delivery of {} to session {}: device id is owned by {}",
                    destination, sessionId, owner);
            return null; // drop this delivery; the subscription itself is left untouched
        }
        return message;
    }
}
