package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
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
    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate; // used for group broadcast on disconnect

    public WebSocketEventListener(DeviceService deviceService, RoomService roomService,
                                  SimpMessagingTemplate messagingTemplate) {
        this.deviceService = deviceService;
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String deviceId = deviceService.getDeviceIdBySessionId(sessionId);

        log.info("Connection lost! Session: {}, Device: {}", sessionId, deviceId);

        if (deviceId != null) {
            String roomCode = roomService.leaveRoom(deviceId);
            if (roomCode != null) {
                roomService.broadcastRoomUpdate(roomCode);
            }

            String group = deviceService.getGroup(deviceId);
            deviceService.unregisterDevice(deviceId, sessionId);
            if (group != null) {
                List<String> devices = deviceService.getActiveDevicesInGroup(group);
                log.info("Group [{}] being updated after disconnect..", group);
                for (String id : devices) {
                    messagingTemplate.convertAndSend("/topic/devices/" + id, devices);
                }
            }
        }
    }
}