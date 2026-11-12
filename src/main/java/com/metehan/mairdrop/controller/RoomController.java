package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomCreateRateLimiter;
import com.metehan.mairdrop.service.RoomJoinRateLimiter;
import com.metehan.mairdrop.service.RoomService;
import com.metehan.mairdrop.util.CommonConstants;
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
    private final RoomJoinRateLimiter roomJoinRateLimiter;
    private final RoomCreateRateLimiter roomCreateRateLimiter;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomController(RoomService roomService, DeviceService deviceService,
                          RoomJoinRateLimiter roomJoinRateLimiter,
                          RoomCreateRateLimiter roomCreateRateLimiter,
                          SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.deviceService = deviceService;
        this.roomJoinRateLimiter = roomJoinRateLimiter;
        this.roomCreateRateLimiter = roomCreateRateLimiter;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/rooms/create")
    public void createRoom(SimpMessageHeaderAccessor headerAccessor) {
        String deviceId = resolveDeviceId(headerAccessor);
        if (deviceId == null) return;

        // Throttle per-device creation so one client cannot pin rooms open until their close grace
        // elapses (and spam every peer with broadcasts) faster than they age out. The check and the
        // record are one atomic step (tryAcquire), so concurrent creates cannot all pass the limit.
        if (!roomCreateRateLimiter.tryAcquire(deviceId)) {
            log.warn("Room creation throttled for device {}", deviceId);
            messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                    Map.of("type", "ROOM_CREATE_LIMITED",
                            "message", "Too many rooms created recently. Please wait a moment and try again."));
            return;
        }

        String oldRoomCode = roomService.getRoomCode(deviceId);
        String roomCode = roomService.createRoom(deviceId);
        if (roomCode == null) {
            // The global concurrent-room cap was reached (RoomService logs the reason). Give the
            // per-device reservation back so a refused creation does not consume its allowance.
            roomCreateRateLimiter.rollback(deviceId);
            messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                    Map.of("type", "ROOM_CREATE_LIMITED",
                            "message", "The server cannot host more rooms right now. Please try again later."));
            return;
        }

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

        // Throttle guessing before touching the room registry, so the 5-letter code space can't be
        // brute-forced through the ROOM_JOINED/ROOM_INVALID oracle.
        String rateKey = rateLimitKey(headerAccessor, deviceId);
        if (!roomJoinRateLimiter.isAllowed(rateKey)) {
            log.warn("Room join throttled for device {} (too many failed attempts)", deviceId);
            messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                    Map.of("type", "ROOM_RATE_LIMITED"));
            return;
        }

        roomCode = roomCode.trim().toUpperCase();
        String oldRoomCode = roomService.getRoomCode(deviceId);

        boolean joined = roomService.joinRoom(deviceId, roomCode);
        if (!joined) {
            roomJoinRateLimiter.recordFailure(rateKey);
            // Echo the attempted code so the client can tell an expired auto-rejoin (matches the
            // room it thinks it is in — clear it) from a mistyped manual join (leave state alone).
            messagingTemplate.convertAndSend("/topic/room/" + deviceId,
                    Map.of("type", "ROOM_INVALID", "roomCode", roomCode));
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

    // Rate-limit by the client IP resolved at the handshake (a brute-forcer can rotate device ids by
    // reconnecting, but not its address); fall back to the device id when no IP was recorded.
    private String rateLimitKey(SimpMessageHeaderAccessor headerAccessor, String deviceId) {
        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        Object clientIp = (attributes != null) ? attributes.get(CommonConstants.SESSION_CLIENT_IP) : null;
        return (clientIp != null) ? clientIp.toString() : deviceId;
    }
}
