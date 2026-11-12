package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.GroupBroadcaster;
import com.metehan.mairdrop.util.CommonConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketControllerTest {

    @Mock
    private DeviceService deviceService;

    @Mock
    private GroupBroadcaster groupBroadcaster;

    @InjectMocks
    private WebSocketController webSocketController;

    private final String deviceId = "test-device-123";
    private final String token = "secret-token";
    private final String sessionId = "session-xyz";
    private final String group = "LOCAL_NETWORK";

    private SimpMessageHeaderAccessor headerWithGroup() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(CommonConstants.NETWORK_GROUP, group);
        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);
        when(headerAccessor.getSessionId()).thenReturn(sessionId);
        return headerAccessor;
    }

    @Test
    @DisplayName("The device must be registered (with its token) and the group re-broadcast.")
    void shouldRegisterDeviceAndBroadcastList() {
        SimpMessageHeaderAccessor headerAccessor = headerWithGroup();
        when(deviceService.registerDevice(deviceId, token, sessionId, group)).thenReturn(true);

        webSocketController.register(deviceId + "|" + token, headerAccessor);

        verify(deviceService).registerDevice(deviceId, token, sessionId, group);
        verify(groupBroadcaster).broadcastGroup(group);
    }

    @Test
    @DisplayName("A bare deviceId (no token separator) still registers with a null token.")
    void shouldRegisterTokenlessPayload() {
        SimpMessageHeaderAccessor headerAccessor = headerWithGroup();
        when(deviceService.registerDevice(deviceId, null, sessionId, group)).thenReturn(true);

        webSocketController.register(deviceId, headerAccessor);

        verify(deviceService).registerDevice(deviceId, null, sessionId, group);
        verify(groupBroadcaster).broadcastGroup(group);
    }

    @Test
    @DisplayName("A rejected registration (token mismatch) must not broadcast.")
    void shouldNotBroadcastWhenRegistrationRejected() {
        SimpMessageHeaderAccessor headerAccessor = headerWithGroup();
        when(deviceService.registerDevice(deviceId, token, sessionId, group)).thenReturn(false);

        webSocketController.register(deviceId + "|" + token, headerAccessor);

        verify(groupBroadcaster, never()).broadcastGroup(anyString());
    }

    @Test
    @DisplayName("Registration with null deviceId should be dropped silently")
    void shouldDropRegistrationWhenDeviceIdIsNull() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);

        webSocketController.register(null, headerAccessor);

        verify(deviceService, never()).registerDevice(anyString(), any(), anyString(), anyString());
        verify(groupBroadcaster, never()).broadcastGroup(anyString());
    }

    @Test
    @DisplayName("Registration with blank deviceId should be dropped silently")
    void shouldDropRegistrationWhenDeviceIdIsBlank() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);

        webSocketController.register("   ", headerAccessor);

        verify(deviceService, never()).registerDevice(anyString(), any(), anyString(), anyString());
        verify(groupBroadcaster, never()).broadcastGroup(anyString());
    }

    @Test
    @DisplayName("Registration must not NPE when sessionAttributes is null")
    void shouldDropRegistrationWhenSessionAttributesNull() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionAttributes()).thenReturn(null);

        webSocketController.register(deviceId, headerAccessor);

        verify(deviceService, never()).registerDevice(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Registration must be dropped when NETWORK_GROUP attribute missing")
    void shouldDropRegistrationWhenGroupMissing() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionAttributes()).thenReturn(new HashMap<>());

        webSocketController.register(deviceId, headerAccessor);

        verify(deviceService, never()).registerDevice(anyString(), any(), anyString(), anyString());
    }
}
