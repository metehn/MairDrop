package com.metehan.mairdrop.service;

import com.metehan.mairdrop.model.Room;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final long GRACE_PERIOD_MINUTES = 15;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 5;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> deviceToRoom = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom random = new SecureRandom();
    private final SimpMessagingTemplate messagingTemplate;

    public RoomService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastRoomUpdate(String roomCode) {
        List<String> devices = getDevicesInRoom(roomCode);
        for (String deviceId : devices) {
            messagingTemplate.convertAndSend("/topic/devices/" + deviceId, devices);
        }
    }

    public synchronized String createRoom(String deviceId) {
        String code;
        Room room;
        do {
            code = generateCode();
            room = new Room(code);
        } while (rooms.putIfAbsent(code, room) != null);
        addToRoom(deviceId, room);
        log.info("Room {} created by device {}", code, deviceId);
        return code;
    }

    public synchronized boolean joinRoom(String deviceId, String code) {
        code = code.toUpperCase();
        Room room = rooms.get(code);
        if (room == null) {
            log.warn("Device {} tried to join non-existent room {}", deviceId, code);
            return false;
        }
        cancelCloseTimer(room);
        leaveRoom(deviceId);
        addToRoom(deviceId, room);
        log.info("Device {} joined room {}", deviceId, code);
        return true;
    }

    public synchronized String leaveRoom(String deviceId) {
        String code = deviceToRoom.remove(deviceId);
        if (code == null) return null;
        Room room = rooms.get(code);
        if (room == null) return code;
        room.getDeviceIds().remove(deviceId);
        log.info("Device {} left room {}", deviceId, code);
        if (room.getDeviceIds().isEmpty()) {
            scheduleClose(room);
        }
        return code;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public String getRoomCode(String deviceId) {
        return deviceToRoom.get(deviceId);
    }

    public List<String> getDevicesInRoom(String code) {
        Room room = rooms.get(code.toUpperCase());
        if (room == null) return List.of();
        return new ArrayList<>(room.getDeviceIds());
    }

    public boolean roomExists(String code) {
        return rooms.containsKey(code.toUpperCase());
    }

    private void addToRoom(String deviceId, Room room) {
        room.getDeviceIds().add(deviceId);
        deviceToRoom.put(deviceId, room.getCode());
    }

    private void scheduleClose(Room room) {
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            rooms.remove(room.getCode());
            log.info("Room {} removed after grace period", room.getCode());
        }, GRACE_PERIOD_MINUTES, TimeUnit.MINUTES);
        room.setCloseTimer(timer);
        log.info("Room {} grace period started ({} min)", room.getCode(), GRACE_PERIOD_MINUTES);
    }

    private void cancelCloseTimer(Room room) {
        ScheduledFuture<?> timer = room.getCloseTimer();
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
            room.setCloseTimer(null);
            log.info("Room {} grace period cancelled", room.getCode());
        }
    }

    private String generateCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }
}
