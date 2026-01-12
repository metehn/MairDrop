package com.metehan.mairdrop.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebRTCSignalingControllerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private WebRTCSignalingController signalingController;

    private Map<String, Object> payload;
    private final String targetId = "target-device-456";

    @BeforeEach
    void setUp() {
        payload = new HashMap<>();
        payload.put("targetDeviceId", targetId);
        payload.put("senderDeviceId", "sender-123");
        payload.put("data", "sample-webrtc-data");
    }

    @Test
    @DisplayName("When an offer arrives, it should be directed to the target device's topic.")
    void shouldHandleOfferAndForwardToTarget() {
        signalingController.handleOffer(payload);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("When the answer arrives, it should be directed to the target device's topic.")
    void shouldHandleAnswerAndForwardToTarget() {
        signalingController.handleAnswer(payload);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }

    @Test
    @DisplayName("When the ICE Candidate arrives, it should be redirected to the target device's topic.")
    void shouldHandleIceCandidateAndForwardToTarget() {
        signalingController.handleIceCandidate(payload);

        verify(messagingTemplate).convertAndSend("/topic/webrtc/" + targetId, payload);
    }
}