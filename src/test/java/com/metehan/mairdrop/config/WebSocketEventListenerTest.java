package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock
    private DeviceService deviceService;

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private WebSocketEventListener webSocketEventListener;

    private SessionDisconnectEvent disconnectEvent;
    private final String sessionId = "test-session-123";
    private final String deviceId = "device-abc";
    private final String group = "LOCAL_NETWORK";

    @BeforeEach
    void setUp() {
        disconnectEvent = mock(SessionDisconnectEvent.class);
        when(disconnectEvent.getSessionId()).thenReturn(sessionId);
    }

    @Test
    @DisplayName("When the connection is lost, the device should be deleted and the updated list should be sent to the group members.")
    void shouldHandleDisconnectAndNotifyGroup() {
        List<String> remainingDevices = Arrays.asList("device-1", "device-2");

        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(remainingDevices);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService).unregisterDevice(deviceId, sessionId);

        verify(deviceService).getActiveDevicesInGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/device-1", remainingDevices);
        verify(messagingTemplate).convertAndSend("/topic/devices/device-2", remainingDevices);

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), anyList());
    }

    @Test
    @DisplayName("If deviceId is null, no action should be taken.")
    void shouldDoNothingWhenDeviceIdIsNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService, never()).unregisterDevice(anyString(), anyString());
        verify(messagingTemplate, never()).convertAndSend(anyString(), anyList());
    }

    @Test
    @DisplayName("If group information cannot be found, simply delete the device; do not send any messages.")
    void shouldOnlyUnregisterWhenGroupIsNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(null);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService).unregisterDevice(deviceId, sessionId);
        verify(messagingTemplate, never()).convertAndSend(anyString(), anyList());
    }
}