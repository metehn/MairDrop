package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.util.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authorises inbound STOMP frames so a client can only listen on its own device's topics.
 *
 * <p>The SimpleBroker itself performs no authorisation: without this, any connection could subscribe
 * to {@code /topic/**​/{someoneElsesDeviceId}} and eavesdrop on that device's peer list, room events
 * (including the room code), WebRTC signalling (file names/sizes/hashes, SDP, ICE) and visibility
 * changes. On CONNECT this binds the client's device id to the session after proving ownership with
 * its token; every SUBSCRIBE is then required to target that same device id, and both SUBSCRIBE and
 * SEND additionally re-check the identity registry so a session whose claim was superseded (another
 * session claimed the same id with the same token) or expired cannot keep acting on the id.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final Set<String> DEVICE_SCOPED_TOPICS = Set.of("devices", "webrtc", "room", "visibility");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final ConnectionIdentityRegistry identityRegistry;

    public StompAuthChannelInterceptor(ConnectionIdentityRegistry identityRegistry) {
        this.identityRegistry = identityRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            authorizeConnect(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(accessor);
        } else if (command == StompCommand.SEND) {
            authorizeSend(accessor);
        }
        return message;
    }

    private void authorizeConnect(StompHeaderAccessor accessor) {
        String deviceId = accessor.getFirstNativeHeader(CommonConstants.HEADER_DEVICE_ID);
        String token = accessor.getFirstNativeHeader(CommonConstants.HEADER_TOKEN);
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("STOMP CONNECT rejected: no deviceId header");
            throw new MessagingException("A deviceId is required to connect");
        }
        if (!identityRegistry.claim(accessor.getSessionId(), deviceId, token)) {
            log.warn("STOMP CONNECT rejected for {}: device id already held by a different token", deviceId);
            throw new MessagingException("Device id is already in use");
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null) {
            attributes.put(CommonConstants.SESSION_DEVICE_ID, deviceId);
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        // The SimpleBroker resolves every subscription through AntPathMatcher, so '?', '{var}' and
        // '**' are live pattern syntax, not literal text: subscribing to /topic/webrtc/dev_?????????
        // would match every device's signalling topic. Reject any destination carrying pattern syntax.
        if (PATH_MATCHER.isPattern(destination)) {
            log.warn("SUBSCRIBE rejected: pattern destination {}", destination);
            throw new MessagingException("Pattern subscriptions are not allowed");
        }
        String[] parts = destination.split("/");
        // Only device-scoped topics ("/topic/{type}/{deviceId}") are guarded; anything else passes.
        if (parts.length < 4 || !"topic".equals(parts[1]) || !DEVICE_SCOPED_TOPICS.contains(parts[2])) {
            return;
        }
        String target = parts[3];
        // The target must look like a real device id ("dev_" + hex). This guarantees the equality
        // check below compares two well-formed ids, and no malformed shape can ever address another
        // device's topic through the broker.
        if (!CommonConstants.DEVICE_ID_PATTERN.matcher(target).matches()) {
            log.warn("SUBSCRIBE rejected: destination {} does not carry a well-formed device id", destination);
            throw new MessagingException("Not a valid device topic");
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        String owner = (attributes != null) ? (String) attributes.get(CommonConstants.SESSION_DEVICE_ID) : null;
        if (owner == null || !owner.equals(target)) {
            log.warn("SUBSCRIBE rejected: session for {} may not subscribe to {}", owner, destination);
            throw new MessagingException("Not allowed to subscribe to another device's topic");
        }
        // The session attribute is written once at CONNECT and never updated: if a newer session has
        // since claimed this id (same token, e.g. a page refresh while the old tab lingers) or the
        // claim expired, THIS session is no longer the id's owner and must not open new subscriptions
        // to its topics — the broker would otherwise feed it messages the current owner expects.
        String registryOwner = identityRegistry.getOwnerSessionId(target);
        if (!Objects.equals(accessor.getSessionId(), registryOwner)) {
            log.warn("SUBSCRIBE rejected: session {} no longer owns {} (current owner: {})",
                    accessor.getSessionId(), target, registryOwner);
            throw new MessagingException("Device id ownership has moved to another session");
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith("/topic")) {
            log.warn("SEND rejected: clients cannot send directly to broker destination {}", destination);
            throw new MessagingException("Direct sends to /topic are not allowed");
        }
        // A SEND acts on behalf of the device id bound at CONNECT (stamped into the session
        // attributes). Once a newer session claimed that id, this session is superseded: refuse its
        // application frames too, so a stale tab cannot keep creating rooms, sending WebRTC offers or
        // re-registering under an id it no longer owns.
        Map<String, Object> attributes = accessor.getSessionAttributes();
        String deviceId = (attributes != null) ? (String) attributes.get(CommonConstants.SESSION_DEVICE_ID) : null;
        if (deviceId != null) {
            String registryOwner = identityRegistry.getOwnerSessionId(deviceId);
            if (!Objects.equals(accessor.getSessionId(), registryOwner)) {
                log.warn("SEND rejected: session {} no longer owns {} (current owner: {})",
                        accessor.getSessionId(), deviceId, registryOwner);
                throw new MessagingException("Device id ownership has moved to another session");
            }
        }
    }
}
