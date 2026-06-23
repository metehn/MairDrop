package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
public class RoomController {
    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final DeviceService deviceService;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomController(RoomService roomService, DeviceService deviceService,
                          SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.deviceService = deviceService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/rooms/create")
    public void createRoom(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String oldRoomCode = roomService.getRoomCode(deviceId);
        String roomCode = roomService.createRoom(deviceId);

        if (oldRoomCode != null) roomService.broadcastRoomUpdate(oldRoomCode);

        messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_CREATED", "roomCode", roomCode));

        roomService.broadcastRoomUpdate(roomCode);
        log.info("Device {} created room {}", deviceId, roomCode);
    }

    @MessageMapping("/rooms/join")
    public void joinRoom(@Payload String roomCode, SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        roomCode = roomCode.trim().toUpperCase();
        String oldRoomCode = roomService.getRoomCode(deviceId);

        boolean joined = roomService.joinRoom(deviceId, roomCode);
        if (!joined) {
            messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                    Map.of("type", "ROOM_INVALID"));
            return;
        }

        if (oldRoomCode != null && !oldRoomCode.equals(roomCode)) {
            roomService.broadcastRoomUpdate(oldRoomCode);
        }

        messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_JOINED", "roomCode", roomCode));

        roomService.broadcastRoomUpdate(roomCode);
        log.info("Device {} joined room {}", deviceId, roomCode);
    }

    @MessageMapping("/rooms/leave")
    public void leaveRoom(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String roomCode = roomService.leaveRoom(deviceId);
        if (roomCode == null) {
            // Already removed from the room's member set (e.g. via visibility/room/hide,
            // which leaves the room immediately and only remembers it as "pending"), but the
            // client still thinks it's in the room. Fall back to the pending code so leave
            // still clears state and notifies the client instead of silently no-op'ing.
            roomCode = deviceService.getPendingRoomCode(deviceId);
            if (roomCode == null) return;
        }
        deviceService.setPendingRoomCode(deviceId, null);

        messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_LEFT"));

        roomService.broadcastRoomUpdate(roomCode);

        String group = deviceService.getGroup(deviceId);
        if (group != null) {
            List<String> groupDevices = deviceService.getActiveDevicesInGroup(group);
            messagingTemplate.convertAndSend("/topic/devices/" + deviceId, groupDevices);
        }

        log.info("Device {} left room {}", deviceId, roomCode);
    }

    private String resolveDeviceId(SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        String deviceId = deviceService.getDeviceIdBySessionId(sessionId);
        if (deviceId == null) {
            log.warn("No device found for session {}", sessionId);
        }
        return deviceId;
    }
}
