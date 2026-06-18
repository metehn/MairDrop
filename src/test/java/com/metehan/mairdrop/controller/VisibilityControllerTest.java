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
class VisibilityControllerTest {

    @Mock private DeviceService deviceService;
    @Mock private RoomService roomService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SimpMessageHeaderAccessor headerAccessor;

    @InjectMocks
    private VisibilityController visibilityController;

    private final String sessionId = "sess1";
    private final String deviceId = "dev1";
    private final String group = "groupA";

    @BeforeEach
    void setUp() {
        when(headerAccessor.getSessionId()).thenReturn(sessionId);
    }

    @Test
    @DisplayName("hideFromNetwork: sets hidden and sends NETWORK_HIDDEN")
    void shouldHideFromNetwork() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(List.of("dev2"));

        visibilityController.hideFromNetwork(headerAccessor);

        verify(deviceService).setHidden(deviceId, true);
        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "NETWORK_HIDDEN"));
    }

    @Test
    @DisplayName("hideFromNetwork: does nothing when device not found")
    void shouldDoNothingOnHideNetworkWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        visibilityController.hideFromNetwork(headerAccessor);

        verify(deviceService, never()).setHidden(any(), anyBoolean());
    }

    @Test
    @DisplayName("hideFromNetwork: skips group broadcast when group is null")
    void shouldSkipGroupBroadcastOnHideWhenGroupNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(null);

        visibilityController.hideFromNetwork(headerAccessor);

        verify(deviceService, never()).getActiveDevicesInGroup(any());
        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "NETWORK_HIDDEN"));
    }

    @Test
    @DisplayName("showOnNetwork: sets visible and sends NETWORK_VISIBLE")
    void shouldShowOnNetwork() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(List.of("dev1", "dev2"));

        visibilityController.showOnNetwork(headerAccessor);

        verify(deviceService).setHidden(deviceId, false);
        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "NETWORK_VISIBLE"));
    }

    @Test
    @DisplayName("showOnNetwork: does nothing when device not found")
    void shouldDoNothingOnShowNetworkWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        visibilityController.showOnNetwork(headerAccessor);

        verify(deviceService, never()).setHidden(any(), anyBoolean());
    }

    @Test
    @DisplayName("hideFromRoom: leaves room and sends ROOM_HIDDEN")
    void shouldHideFromRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn("ABCDE");

        visibilityController.hideFromRoom(headerAccessor);

        verify(deviceService).setPendingRoomCode(deviceId, "ABCDE");
        verify(roomService).broadcastRoomUpdate("ABCDE");
        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "ROOM_HIDDEN"));
    }

    @Test
    @DisplayName("hideFromRoom: does nothing when device not in room")
    void shouldDoNothingWhenNotInRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        visibilityController.hideFromRoom(headerAccessor);

        verify(deviceService, never()).setPendingRoomCode(any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("hideFromRoom: does nothing when device not found")
    void shouldDoNothingOnHideRoomWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        visibilityController.hideFromRoom(headerAccessor);

        verify(roomService, never()).leaveRoom(any());
    }

    @Test
    @DisplayName("showInRoom: rejoins room and sends ROOM_VISIBLE")
    void shouldShowInRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getPendingRoomCode(deviceId)).thenReturn("ABCDE");
        when(roomService.joinRoom(deviceId, "ABCDE")).thenReturn(true);

        visibilityController.showInRoom(headerAccessor);

        verify(deviceService).setPendingRoomCode(deviceId, null);
        verify(roomService).broadcastRoomUpdate("ABCDE");
        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "ROOM_VISIBLE", "roomCode", "ABCDE"));
    }

    @Test
    @DisplayName("showInRoom: sends ROOM_INVALID when room expired")
    void shouldSendRoomInvalidWhenRoomExpired() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getPendingRoomCode(deviceId)).thenReturn("ABCDE");
        when(roomService.joinRoom(deviceId, "ABCDE")).thenReturn(false);

        visibilityController.showInRoom(headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/visibility/" + deviceId,
                Map.of("type", "ROOM_INVALID"));
        verify(roomService, never()).broadcastRoomUpdate(any());
    }

    @Test
    @DisplayName("showInRoom: does nothing when no pending room")
    void shouldDoNothingWhenNoPendingRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getPendingRoomCode(deviceId)).thenReturn(null);

        visibilityController.showInRoom(headerAccessor);

        verify(roomService, never()).joinRoom(any(), any());
    }

    @Test
    @DisplayName("showInRoom: does nothing when device not found")
    void shouldDoNothingOnShowRoomWhenDeviceNotFound() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        visibilityController.showInRoom(headerAccessor);

        verify(deviceService, never()).getPendingRoomCode(any());
    }
}
