package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;


@Component
public class WebSocketEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final DeviceService deviceService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(DeviceService deviceService, SimpMessagingTemplate messagingTemplate) {
        this.deviceService = deviceService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String deviceId = deviceService.getDeviceIdBySessionId(sessionId);

        log.info("Connecion lost! Session: {}, Device: {}", sessionId, deviceId);

        if (deviceId != null) {
            String group = deviceService.getGroup(deviceId);
            deviceService.unregisterDevice(deviceId);
            if (group != null) {
                List<String> devices = deviceService.getActiveDevicesInGroup(group);
                log.info("Group [{}] It is being updated after being disconnected..", group);
                for (String id : devices) {
                    messagingTemplate.convertAndSend("/topic/devices/" + id, devices);
                }
            }
        }
    }
}