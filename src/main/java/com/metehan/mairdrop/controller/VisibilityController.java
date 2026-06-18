package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
public class VisibilityController {
    private static final Logger log = LoggerFactory.getLogger(VisibilityController.class);

    private final DeviceService deviceService;
    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    public VisibilityController(DeviceService deviceService, RoomService roomService,
                                SimpMessagingTemplate messagingTemplate) {
        this.deviceService = deviceService;
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/visibility/network/hide")
    public void hideFromNetwork(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String group = deviceService.getGroup(deviceId);
        deviceService.setHidden(deviceId, true);
        broadcastGroup(group);

        messagingTemplate.convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "NETWORK_HIDDEN"));
        log.info("Device {} hidden from network", deviceId);
    }

    @MessageMapping("/visibility/network/show")
    public void showOnNetwork(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String group = deviceService.getGroup(deviceId);
        deviceService.setHidden(deviceId, false);
        broadcastGroup(group);

        messagingTemplate.convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "NETWORK_VISIBLE"));
        log.info("Device {} visible on network", deviceId);
    }

    @MessageMapping("/visibility/room/hide")
    public void hideFromRoom(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String roomCode = roomService.leaveRoom(deviceId);
        if (roomCode == null) return; // not in a room — no-op

        deviceService.setPendingRoomCode(deviceId, roomCode);
        roomService.broadcastRoomUpdate(roomCode);

        String group = deviceService.getGroup(deviceId);
        if (group != null) {
            messagingTemplate.convertAndSend("/topic/devices/" + deviceId,
                    deviceService.getActiveDevicesInGroup(group));
        }

        messagingTemplate.convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "ROOM_HIDDEN"));
        log.info("Device {} hidden from room {}", deviceId, roomCode);
    }

    @MessageMapping("/visibility/room/show")
    public void showInRoom(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        String pendingRoom = deviceService.getPendingRoomCode(deviceId);
        if (pendingRoom == null) return;

        deviceService.setPendingRoomCode(deviceId, null);
        boolean joined = roomService.joinRoom(deviceId, pendingRoom);
        if (!joined) {
            messagingTemplate.convertAndSend("/topic/visibility/" + deviceId,
                    Map.of("type", "ROOM_INVALID"));
            log.warn("Device {} tried to rejoin expired room {}", deviceId, pendingRoom);
            return;
        }

        roomService.broadcastRoomUpdate(pendingRoom);
        messagingTemplate.convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "ROOM_VISIBLE", "roomCode", pendingRoom));
        log.info("Device {} visible in room {}", deviceId, pendingRoom);
    }

    private void broadcastGroup(String group) {
        if (group == null) return;
        List<String> devices = deviceService.getActiveDevicesInGroup(group);
        for (String id : devices) {
            // Skip devices in a room — their room view must not be overwritten by a network broadcast
            if (roomService.getRoomCode(id) == null) {
                messagingTemplate.convertAndSend("/topic/devices/" + id, devices);
            }
        }
    }

    private String resolveDeviceId(SimpMessageHeaderAccessor h) {
        String deviceId = deviceService.getDeviceIdBySessionId(h.getSessionId());
        if (deviceId == null) log.warn("No device for session {}", h.getSessionId());
        return deviceId;
    }
}
