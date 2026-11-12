package com.metehan.mairdrop.service;

import com.metehan.mairdrop.model.Room;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    static final long GRACE_PERIOD_MINUTES = 15;
    private static final long DEFAULT_GRACE_PERIOD_MS = GRACE_PERIOD_MINUTES * 60_000L;
    static final int DEFAULT_MAX_ROOMS = 200;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 5;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> deviceToRoom = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final SecureRandom random = new SecureRandom();
    private final SimpMessagingTemplate messagingTemplate;
    private final int maxRooms;
    private final long gracePeriodMs;

    public RoomService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, DEFAULT_MAX_ROOMS, DEFAULT_GRACE_PERIOD_MS, defaultScheduler());
    }

    @Autowired
    public RoomService(SimpMessagingTemplate messagingTemplate,
                       @Value("${mairdrop.max-rooms:" + DEFAULT_MAX_ROOMS + "}") int maxRooms) {
        this(messagingTemplate, maxRooms, DEFAULT_GRACE_PERIOD_MS, defaultScheduler());
    }

    /**
     * Test seam: lets tests drive the close timer with a short grace period and their own scheduler
     * (the default 15-minute grace would make timer behaviour untestable).
     */
    RoomService(SimpMessagingTemplate messagingTemplate, int maxRooms, long gracePeriodMs,
                ScheduledExecutorService scheduler) {
        this.messagingTemplate = messagingTemplate;
        this.maxRooms = maxRooms;
        this.gracePeriodMs = gracePeriodMs;
        this.scheduler = scheduler;
    }

    private static ScheduledExecutorService defaultScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public void broadcastRoomUpdate(String roomCode) {
        List<String> devices = getDevicesInRoom(roomCode);
        for (String deviceId : devices) {
            messagingTemplate.convertAndSend("/topic/devices/" + deviceId, devices);
        }
    }

    public synchronized String createRoom(String deviceId) {
        if (rooms.size() >= maxRooms) {
            log.warn("Room creation refused for {}: server already hosts {} rooms (limit {})",
                    deviceId, rooms.size(), maxRooms);
            return null;
        }
        String code;
        Room room;
        do {
            code = generateCode();
            room = new Room(code);
        } while (rooms.putIfAbsent(code, room) != null);
        leaveRoom(deviceId);
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
        leaveCurrentAndPreserve(deviceId, room);
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

    /**
     * Removes {@code deviceId} from whatever room it is currently in, while guaranteeing
     * {@code targetRoom}'s close timer stays cancelled. This matters when the device is
     * rejoining the SAME room: {@link #leaveRoom} empties it and schedules a close, which
     * the second cancel immediately undoes.
     */
    private void leaveCurrentAndPreserve(String deviceId, Room targetRoom) {
        cancelCloseTimer(targetRoom);
        leaveRoom(deviceId);
        cancelCloseTimer(targetRoom);
    }

    private void scheduleClose(Room room) {
        ScheduledFuture<?> timer = scheduler.schedule(() -> closeIfStillEmpty(room),
                gracePeriodMs, TimeUnit.MILLISECONDS);
        room.setCloseTimer(timer);
        log.info("Room {} grace period started ({} ms)", room.getCode(), gracePeriodMs);
    }

    // Runs under the same lock as join/leave so a device joining at the exact moment the timer
    // fires can't be dropped into a room that is being removed. remove(key, value) also guards
    // against evicting a different room that happens to have reused this code.
    private synchronized void closeIfStillEmpty(Room room) {
        if (room.getDeviceIds().isEmpty()) {
            rooms.remove(room.getCode(), room);
            log.info("Room {} removed after grace period", room.getCode());
        }
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
