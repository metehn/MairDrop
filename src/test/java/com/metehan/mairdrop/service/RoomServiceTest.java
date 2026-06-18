package com.metehan.mairdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(messagingTemplate);
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
}
