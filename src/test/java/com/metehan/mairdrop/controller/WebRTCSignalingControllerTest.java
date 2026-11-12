package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import com.metehan.mairdrop.util.CommonConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebRTCSignalingControllerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private DeviceService deviceService;
    @Mock private RoomService roomService;

    @InjectMocks
    private WebRTCSignalingController signalingController;

    private final String senderId = "sender-123";
    private final String targetId = "target-456";
    private SimpMessageHeaderAccessor headerAccessor;
    private Map<String, Object> payload;

    @BeforeEach
    void setUp() {
        headerAccessor = mock(SimpMessageHeaderAccessor.class);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(CommonConstants.SESSION_DEVICE_ID, senderId);
        lenient().when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);

        payload = new HashMap<>();
        payload.put("targetDeviceId", targetId);
        payload.put("senderDeviceId", "spoofed-sender");
    }

    @Test
    @DisplayName("offer is forwarded when sender and target share a visible network group")
    void offerForwardedWhenSameGroup() {
        when(deviceService.getGroup(senderId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.getGroup(targetId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.isHidden(targetId)).thenReturn(false);

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("offer is forwarded when sender and target are in the same room")
    void offerForwardedWhenSameRoom() {
        when(roomService.getRoomCode(senderId)).thenReturn("ABCDE");
        when(roomService.getRoomCode(targetId)).thenReturn("ABCDE");

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("offer is dropped when sender and target share no room or network group")
    void offerDroppedWhenCrossScope() {
        when(deviceService.getGroup(senderId)).thenReturn("1.2.3.4");
        when(deviceService.getGroup(targetId)).thenReturn("5.6.7.8");

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(eq("/topic/webrtc/" + targetId), any(Object.class));
    }

    @Test
    @DisplayName("offer is dropped when the target has hidden itself from the network")
    void offerDroppedWhenTargetHidden() {
        when(deviceService.getGroup(senderId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.getGroup(targetId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.isHidden(targetId)).thenReturn(true);

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(eq("/topic/webrtc/" + targetId), any(Object.class));
    }

    @Test
    @DisplayName("refused offer notifies the sender so its UI does not hang until the handshake timeout")
    void refusedOfferNotifiesSender() {
        when(deviceService.getGroup(senderId)).thenReturn("1.2.3.4");
        when(deviceService.getGroup(targetId)).thenReturn("5.6.7.8");

        signalingController.handleOffer(payload, headerAccessor);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/webrtc/" + senderId), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> refusal = (Map<String, Object>) captor.getValue();
        assertEquals(Boolean.TRUE, refusal.get("offerRefused"));
        assertEquals(targetId, refusal.get("senderDeviceId"),
                "the client keys its connection state by the unreachable peer's id");
    }

    @Test
    @DisplayName("refused offer to a hidden target notifies the sender too")
    void refusedOfferToHiddenTargetNotifiesSender() {
        when(deviceService.getGroup(senderId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.getGroup(targetId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.isHidden(targetId)).thenReturn(true);

        signalingController.handleOffer(payload, headerAccessor);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/webrtc/" + senderId), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> refusal = (Map<String, Object>) captor.getValue();
        assertEquals(Boolean.TRUE, refusal.get("offerRefused"));
    }

    @Test
    @DisplayName("the offer's senderDeviceId is overwritten with the authenticated session identity")
    void senderIsStampedFromSession() {
        when(deviceService.getGroup(senderId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.getGroup(targetId)).thenReturn("LOCAL_NETWORK");
        when(deviceService.isHidden(targetId)).thenReturn(false);

        signalingController.handleOffer(payload, headerAccessor);

        assertEquals(senderId, payload.get("senderDeviceId"),
                "the spoofed sender must be replaced by the real session device id");
    }

    @Test
    @DisplayName("offer from an unidentified (unbound) session is dropped")
    void offerDroppedWhenSenderUnidentified() {
        when(headerAccessor.getSessionAttributes()).thenReturn(new HashMap<>());

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("answer is forwarded to the target with the sender stamped")
    void answerForwardedAndStamped() {
        signalingController.handleAnswer(payload, headerAccessor);

        assertEquals(senderId, payload.get("senderDeviceId"));
        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("ICE candidate is forwarded to the target")
    void iceCandidateForwarded() {
        signalingController.handleIceCandidate(payload, headerAccessor);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("decline is forwarded and the sender is stamped so it cannot be spoofed")
    void declineForwardedAndStamped() {
        signalingController.handleDecline(payload, headerAccessor);

        assertEquals(senderId, payload.get("senderDeviceId"));
        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("answer with a missing target is dropped")
    void answerDroppedWhenTargetMissing() {
        payload.remove("targetDeviceId");

        signalingController.handleAnswer(payload, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("offer with a missing target is dropped")
    void offerDroppedWhenTargetMissing() {
        payload.remove("targetDeviceId");

        signalingController.handleOffer(payload, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }
}
