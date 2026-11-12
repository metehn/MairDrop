package com.metehan.mairdrop.config;

import com.metehan.mairdrop.service.ConnectionIdentityRegistry;
import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.GroupBroadcaster;
import com.metehan.mairdrop.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock
    private DeviceService deviceService;

    @Mock
    private RoomService roomService;

    @Mock
    private GroupBroadcaster groupBroadcaster;

    @Mock
    private ConnectionIdentityRegistry identityRegistry;

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
    @DisplayName("When the connection is lost, the device is removed and the group re-broadcast.")
    void shouldHandleDisconnectAndNotifyGroup() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.unregisterDevice(deviceId, sessionId)).thenReturn(true);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService).unregisterDevice(deviceId, sessionId);
        verify(groupBroadcaster).broadcastGroup(group);
    }

    @Test
    @DisplayName("A device that was in a room broadcasts that room's roster on disconnect.")
    void shouldBroadcastRoomOnDisconnectWhenInRoom() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.unregisterDevice(deviceId, sessionId)).thenReturn(true);
        when(roomService.leaveRoom(deviceId)).thenReturn("ABCDE");

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(roomService).broadcastRoomUpdate("ABCDE");
        verify(groupBroadcaster).broadcastGroup(group);
    }

    @Test
    @DisplayName("A stale disconnect (device already reconnected) must not leave its room or broadcast.")
    void shouldIgnoreStaleDisconnect() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(group);
        when(deviceService.unregisterDevice(deviceId, sessionId)).thenReturn(false);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(roomService, never()).leaveRoom(anyString());
        verify(groupBroadcaster, never()).broadcastGroup(anyString());
    }

    @Test
    @DisplayName("If deviceId is null, no action should be taken.")
    void shouldDoNothingWhenDeviceIdIsNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService, never()).unregisterDevice(anyString(), anyString());
        verify(groupBroadcaster, never()).broadcastGroup(anyString());
    }

    @Test
    @DisplayName("If group is null, the device is still unregistered; broadcastGroup(null) no-ops.")
    void shouldOnlyUnregisterWhenGroupIsNull() {
        when(deviceService.getDeviceIdBySessionId(sessionId)).thenReturn(deviceId);
        when(deviceService.getGroup(deviceId)).thenReturn(null);
        when(deviceService.unregisterDevice(deviceId, sessionId)).thenReturn(true);
        when(roomService.leaveRoom(deviceId)).thenReturn(null);

        webSocketEventListener.handleDisconnect(disconnectEvent);

        verify(deviceService).unregisterDevice(deviceId, sessionId);
        verify(groupBroadcaster).broadcastGroup(null);
    }
}
