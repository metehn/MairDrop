package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.GroupBroadcaster;
import com.metehan.mairdrop.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;


@Component
public class WebSocketEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final DeviceService deviceService;
    private final RoomService roomService;
    private final GroupBroadcaster groupBroadcaster;
    private final ConnectionIdentityRegistry identityRegistry;

    public WebSocketEventListener(DeviceService deviceService, RoomService roomService,
                                  GroupBroadcaster groupBroadcaster,
                                  ConnectionIdentityRegistry identityRegistry) {
        this.deviceService = deviceService;
        this.roomService = roomService;
        this.groupBroadcaster = groupBroadcaster;
        this.identityRegistry = identityRegistry;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String deviceId = deviceService.getDeviceIdBySessionId(sessionId);

        log.info("Connection lost! Session: {}, Device: {}", sessionId, deviceId);

        // Free the device-id ownership so a legitimate reconnect (or a new owner) can reclaim it.
        identityRegistry.release(sessionId);

        if (deviceId != null) {
            String group = deviceService.getGroup(deviceId);

            // Unregister first so a stale disconnect (one whose device already reconnected on a new
            // session) is rejected here and does NOT go on to tear down the reconnected device's
            // room membership below.
            boolean removed = deviceService.unregisterDevice(deviceId, sessionId);
            if (!removed) {
                log.warn("Disconnect for {} ignored as stale; room and group state left intact", deviceId);
                return;
            }

            String roomCode = roomService.leaveRoom(deviceId);
            if (roomCode != null) {
                roomService.broadcastRoomUpdate(roomCode);
            }

            log.info("Group [{}] being updated after disconnect..", group);
            groupBroadcaster.broadcastGroup(group);
        }
    }
}
