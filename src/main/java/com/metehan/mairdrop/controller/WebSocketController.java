package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.GroupBroadcaster;
import com.metehan.mairdrop.util.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebSocketController {
    private static final Logger log = LoggerFactory.getLogger(WebSocketController.class);
    private final DeviceService deviceService;
    private final GroupBroadcaster groupBroadcaster;

    public WebSocketController(DeviceService deviceService, GroupBroadcaster groupBroadcaster) {
        this.deviceService = deviceService;
        this.groupBroadcaster = groupBroadcaster;
    }

    @MessageMapping("/register")
    public void register(@Payload String payload, SimpMessageHeaderAccessor headerAccessor) {
        // Payload is "deviceId|token" ("token" is a secret the owning browser keeps so nobody else
        // can re-register under its id). A bare "deviceId" (no separator) is accepted tokenless for
        // backward compatibility with clients/tests that predate the token.
        String deviceId = payload;
        String token = null;
        if (payload != null) {
            int sep = payload.indexOf('|');
            if (sep >= 0) {
                deviceId = payload.substring(0, sep);
                token = payload.substring(sep + 1);
            }
        }

        if (deviceId == null || deviceId.isBlank()) {
            log.warn("Registration dropped: deviceId is missing");
            return;
        }
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        String group = (attrs != null) ? (String) attrs.get(CommonConstants.NETWORK_GROUP) : null;
        if (group == null) {
            log.warn("Registration dropped for {}: network group missing (handshake skipped)", deviceId);
            return;
        }
        boolean accepted = deviceService.registerDevice(deviceId, token, headerAccessor.getSessionId(), group);
        if (!accepted) {
            log.warn("Registration request rejected for {} (Group: {})", deviceId, group);
            return;
        }
        log.info("Registration request has arrived: {} -> Group: {}", deviceId, group);
        groupBroadcaster.broadcastGroup(group);
    }
}
