package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.util.CommonConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketControllerTest {

    @Mock
    private DeviceService deviceService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private WebSocketController webSocketController;

    private final String deviceId = "test-device-123";
    private final String sessionId = "session-xyz";
    private final String group = "LOCAL_NETWORK";

    @Test
    @DisplayName("The device must be registered and the updated list sent to everyone in the group.")
    void shouldRegisterDeviceAndBroadcastList() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(CommonConstants.NETWORK_GROUP, group);

        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);
        when(headerAccessor.getSessionId()).thenReturn(sessionId);

        List<String> activeDevices = Arrays.asList("test-device-123", "other-device-456");
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(activeDevices);

        webSocketController.register(deviceId, headerAccessor);

        verify(deviceService).registerDevice(deviceId, sessionId, group);

        verify(deviceService).getActiveDevicesInGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/test-device-123", activeDevices);
        verify(messagingTemplate).convertAndSend("/topic/devices/other-device-456", activeDevices);

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), anyList());
    }
}