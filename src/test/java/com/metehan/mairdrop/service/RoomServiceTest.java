package com.metehan.mairdrop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    private static final long SHORT_GRACE_MS = 200L;
    private static final long MAX_WAIT_MS = 2000L;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private final List<RoomService> createdServices = new ArrayList<>();
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = track(new RoomService(messagingTemplate));
    }

    @AfterEach
    void tearDown() {
        // RoomService owns a non-daemon ScheduledThreadPoolExecutor; shut every instance down so
        // test JVMs do not leak threads.
        for (RoomService service : createdServices) {
            service.shutdown();
        }
    }

    private RoomService track(RoomService service) {
        createdServices.add(service);
        return service;
    }

    private RoomService graceRoomService(int maxRooms, long graceMs) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return track(new RoomService(messagingTemplate, maxRooms, graceMs, executor));
    }

    private RoomService shortGraceRoomService(int maxRooms) {
        return graceRoomService(maxRooms, SHORT_GRACE_MS);
    }

    /** Polls until the room disappears or the deadline passes (timer runs on another thread). */
    private boolean awaitRoomClosed(RoomService service, String code) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!service.roomExists(code)) {
                return true;
            }
            Thread.sleep(50);
        }
        return !service.roomExists(code);
    }

    /**
     * Polls {@code durationMs} asserting the room stays open the whole time. A cancelled close
     * timer (or a still-occupied room) means the room can never close, so this observes the one
     * deterministic state the timer tests can promise instead of a fixed sleep.
     */
    private void assertRoomSurvives(RoomService service, String code, long durationMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            assertTrue(service.roomExists(code), "room must survive the grace window");
            Thread.sleep(50);
        }
        assertTrue(service.roomExists(code), "room must still exist once the grace window elapsed");
    }

    @Test
    @DisplayName("createRoom: returns a 5-char uppercase code and room exists")
    void shouldCreateRoomAndReturnCode() {
        String code = roomService.createRoom("dev1");

        assertNotNull(code);
        assertEquals(5, code.length());
        assertTrue(code.matches("[A-Z]{5}"));
        assertTrue(roomService.roomExists(code));
    }

    @Test
    @DisplayName("createRoom: device is added to the room")
    void shouldAddDeviceToCreatedRoom() {
        String code = roomService.createRoom("dev1");

        assertTrue(roomService.getDevicesInRoom(code).contains("dev1"));
    }

    @Test
    @DisplayName("createRoom: refuses (null) once the global concurrent-room cap is reached")
    void shouldRefuseCreationAtGlobalCap() {
        RoomService capped = shortGraceRoomService(2);

        String code1 = capped.createRoom("dev1");
        String code2 = capped.createRoom("dev2");

        assertNotNull(code1);
        assertNotNull(code2);
        assertNull(capped.createRoom("dev3"), "third room must be refused at the cap");
        assertTrue(capped.roomExists(code1), "existing rooms must survive a refused creation");
        assertTrue(capped.roomExists(code2), "existing rooms must survive a refused creation");
    }

    @Test
    @DisplayName("joinRoom: returns true and adds device for existing room")
    void shouldJoinExistingRoom() {
        String code = roomService.createRoom("dev1");

        boolean result = roomService.joinRoom("dev2", code);

        assertTrue(result);
        assertTrue(roomService.getDevicesInRoom(code).contains("dev2"));
    }

    @Test
    @DisplayName("joinRoom: returns false for non-existent room")
    void shouldReturnFalseForNonExistentRoom() {
        assertFalse(roomService.joinRoom("dev1", "XXXXX"));
    }

    @Test
    @DisplayName("joinRoom: is case-insensitive")
    void shouldJoinRoomCaseInsensitive() {
        String code = roomService.createRoom("dev1");

        assertTrue(roomService.joinRoom("dev2", code.toLowerCase()));
    }

    @Test
    @DisplayName("joinRoom: device switches from old room to new room")
    void shouldSwitchRooms() {
        String code1 = roomService.createRoom("dev1");
        String code2 = roomService.createRoom("dev2");

        roomService.joinRoom("dev1", code2);

        assertFalse(roomService.getDevicesInRoom(code1).contains("dev1"));
        assertTrue(roomService.getDevicesInRoom(code2).contains("dev1"));
    }

    @Test
    @DisplayName("leaveRoom: removes device and returns room code")
    void shouldLeaveRoomAndReturnCode() {
        String code = roomService.createRoom("dev1");

        String returned = roomService.leaveRoom("dev1");

        assertEquals(code, returned);
        assertFalse(roomService.getDevicesInRoom(code).contains("dev1"));
    }

    @Test
    @DisplayName("leaveRoom: returns null when device is not in any room")
    void shouldReturnNullWhenNotInRoom() {
        assertNull(roomService.leaveRoom("dev-unknown"));
    }

    @Test
    @DisplayName("getRoomCode: returns code for device in room")
    void shouldGetRoomCodeForDevice() {
        String code = roomService.createRoom("dev1");

        assertEquals(code, roomService.getRoomCode("dev1"));
    }

    @Test
    @DisplayName("getRoomCode: returns null for device not in any room")
    void shouldReturnNullRoomCodeForDeviceNotInRoom() {
        assertNull(roomService.getRoomCode("dev-unknown"));
    }

    @Test
    @DisplayName("getDevicesInRoom: returns empty list for non-existent room")
    void shouldReturnEmptyListForNonExistentRoom() {
        assertTrue(roomService.getDevicesInRoom("XXXXX").isEmpty());
    }

    @Test
    @DisplayName("roomExists: returns false for non-existent room")
    void shouldReturnFalseForNonExistentRoomExists() {
        assertFalse(roomService.roomExists("XXXXX"));
    }

    @Test
    @DisplayName("broadcastRoomUpdate: sends to all devices in room")
    void shouldBroadcastToAllDevicesInRoom() {
        String code = roomService.createRoom("dev1");
        roomService.joinRoom("dev2", code);

        roomService.broadcastRoomUpdate(code);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/devices/dev1"), anyList());
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/devices/dev2"), anyList());
    }

    @Test
    @DisplayName("broadcastRoomUpdate: does nothing for non-existent room")
    void shouldNotBroadcastForNonExistentRoom() {
        roomService.broadcastRoomUpdate("XXXXX");

        verify(messagingTemplate, never()).convertAndSend(anyString(), anyList());
    }

    @Test
    @DisplayName("shutdown: does not throw")
    void shutdownShouldNotThrow() {
        assertDoesNotThrow(() -> roomService.shutdown());
    }

    @Test
    @DisplayName("timer: an emptied room closes after the grace period elapses")
    void roomClosesAfterLastMemberLeaves() throws InterruptedException {
        RoomService shortGrace = shortGraceRoomService(10);
        String code = shortGrace.createRoom("dev1");
        shortGrace.joinRoom("dev2", code);

        shortGrace.leaveRoom("dev2");
        shortGrace.leaveRoom("dev1");

        assertTrue(awaitRoomClosed(shortGrace, code), "empty room must be removed after the grace period");
    }

    @Test
    @DisplayName("timer: a join before the grace elapses cancels the close and keeps the room")
    void joinBeforeGraceElapsesCancelsClose() throws InterruptedException {
        // A longer grace period for this specific test keeps the "join must win the race against
        // the close timer" step immune to CI scheduling pauses; the assertion itself polls.
        long graceMs = 1000L;
        RoomService room = graceRoomService(10, graceMs);
        String code = room.createRoom("dev1");
        room.joinRoom("dev2", code);
        room.leaveRoom("dev2");
        room.leaveRoom("dev1"); // empty -> close scheduled

        assertTrue(room.joinRoom("dev3", code), "join within the grace period must win the race");

        // Once the join cancelled the timer the room can never close on its own: assert it survives
        // well past the grace window.
        assertRoomSurvives(room, code, graceMs * 3);
        assertTrue(room.getDevicesInRoom(code).contains("dev3"));
    }

    @Test
    @DisplayName("timer: a join after the grace elapsed gets a closed (invalid) room")
    void joinAfterGraceElapsedIsRefused() throws InterruptedException {
        RoomService shortGrace = shortGraceRoomService(10);
        String code = shortGrace.createRoom("dev1");
        shortGrace.joinRoom("dev2", code);
        shortGrace.leaveRoom("dev2");
        shortGrace.leaveRoom("dev1");

        assertTrue(awaitRoomClosed(shortGrace, code));
        assertFalse(shortGrace.joinRoom("dev3", code), "joining a room closed by the timer must fail");
    }

    @Test
    @DisplayName("timer: a room with members is never closed by the grace timer")
    void roomNotClosedWhileMembersPresent() throws InterruptedException {
        RoomService shortGrace = shortGraceRoomService(10);
        String code = shortGrace.createRoom("dev1");
        shortGrace.joinRoom("dev2", code);

        shortGrace.leaveRoom("dev2"); // dev1 remains

        // A room that still has members can never close: observe it survive well past the grace
        // window instead of one fixed sleep.
        assertRoomSurvives(shortGrace, code, SHORT_GRACE_MS * 4);
        assertTrue(shortGrace.getDevicesInRoom(code).contains("dev1"));
    }
}
