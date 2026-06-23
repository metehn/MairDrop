package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomControllerTest {

    @Mock private RoomService roomService;
    @Mock private DeviceService deviceService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SimpMessageHeaderAccessor headerAccessor;

    @InjectMocks
    private RoomController roomController;

    private final String sessionId = "sess1";
    private final String deviceId = "dev1";

    @BeforeEach
    void setUp() {
        when(headerAccessor.getSessionId()).thenReturn(sessionId);
    }

    @Test
    @DisplayName("createRoom: sends ROOM_CREATED and broadcasts")
    void shouldCreateRoomAndNotifyDevice() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.getRoomCode(deviceId)).thenReturn(null);
        when(roomService.createRoom(deviceId)).thenReturn("ABCDE");

        roomController.createRoom(headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_CREATED", "roomCode", "ABCDE"));
        verify(roomService).broadcastRoomUpdate("ABCDE");
    }

    @Test
    @DisplayName("createRoom: broadcasts old room when device was in another room")
    void shouldBroadcastOldRoomOnCreate() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.getRoomCode(deviceId)).thenReturn("OLDCO");
        when(roomService.createRoom(deviceId)).thenReturn("NEWCO");

        roomController.createRoom(headerAccessor);

        verify(roomService).broadcastRoomUpdate("OLDCO");
        verify(roomService).broadcastRoomUpdate("NEWCO");
    }

    @Test
    @DisplayName("createRoom: does nothing when device not found")
    void shouldDoNothingOnCreateWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        roomController.createRoom(headerAccessor);

        verify(roomService, never()).createRoom(any());
    }

    @Test
    @DisplayName("joinRoom: sends ROOM_JOINED on success")
    void shouldJoinRoomAndNotifyDevice() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.getRoomCode(deviceId)).thenReturn(null);
        when(roomService.joinRoom(deviceId, "ABCDE")).thenReturn(true);

        roomController.joinRoom("ABCDE", headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_JOINED", "roomCode", "ABCDE"));
        verify(roomService).broadcastRoomUpdate("ABCDE");
    }

    @Test
    @DisplayName("joinRoom: sends ROOM_INVALID when room not found")
    void shouldSendRoomInvalidWhenJoinFails() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.getRoomCode(deviceId)).thenReturn(null);
        when(roomService.joinRoom(deviceId, "XXXXX")).thenReturn(false);

        roomController.joinRoom("XXXXX", headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_INVALID"));
        verify(roomService, never()).broadcastRoomUpdate(any());
    }

    @Test
    @DisplayName("joinRoom: broadcasts old room when switching rooms")
    void shouldBroadcastOldRoomOnJoin() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.getRoomCode(deviceId)).thenReturn("OLDCO");
        when(roomService.joinRoom(deviceId, "NEWCO")).thenReturn(true);

        roomController.joinRoom("NEWCO", headerAccessor);

        verify(roomService).broadcastRoomUpdate("OLDCO");
        verify(roomService).broadcastRoomUpdate("NEWCO");
    }

    @Test
    @DisplayName("joinRoom: does nothing when device not found")
    void shouldDoNothingOnJoinWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        roomController.joinRoom("ABCDE", headerAccessor);

        verify(roomService, never()).joinRoom(any(), any());
    }

    @Test
    @DisplayName("leaveRoom: sends ROOM_LEFT and updates group devices")
    void shouldLeaveRoomAndNotifyGroup() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn("ABCDE");
        when(deviceService.getGroup(deviceId)).thenReturn("groupA");
        when(deviceService.getActiveDevicesInGroup("groupA")).thenReturn(List.of("dev2"));

        roomController.leaveRoom(headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId,
                Map.of("type", "ROOM_LEFT"));
        verify(roomService).broadcastRoomUpdate("ABCDE");
        verify(messagingTemplate).convertAndSend("/topic/devices/" + deviceId, List.of("dev2"));
    }

    @Test
    @DisplayName("leaveRoom: does nothing when device not in room")
    void shouldDoNothingWhenNotInRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        roomController.leaveRoom(headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("leaveRoom: falls back to the pending room code when membership was already cleared")
    void shouldFallBackToPendingRoomCodeWhenNotInRoomMemberSet() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);
        when(deviceService.getPendingRoomCode(deviceId)).thenReturn("ABCDE");

        roomController.leaveRoom(headerAccessor);

        verify(deviceService).setPendingRoomCode(deviceId, null);
        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId, Map.of("type", "ROOM_LEFT"));
        verify(roomService).broadcastRoomUpdate("ABCDE");
    }

    @Test
    @DisplayName("leaveRoom: does nothing when device not found")
    void shouldDoNothingOnLeaveWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        roomController.leaveRoom(headerAccessor);

        verify(roomService, never()).leaveRoom(any());
    }

    @Test
    @DisplayName("leaveRoom: skips group broadcast when group is null")
    void shouldSkipGroupBroadcastWhenGroupNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn("ABCDE");
        when(deviceService.getGroup(deviceId)).thenReturn(null);

        roomController.leaveRoom(headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/room/" + deviceId, Map.of("type", "ROOM_LEFT"));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/devices/" + deviceId), anyList());
    }
}
