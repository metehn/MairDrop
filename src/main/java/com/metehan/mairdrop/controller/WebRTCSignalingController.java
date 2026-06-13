package com.metehan.mairdrop.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebRTCSignalingController {

    private static final Logger log = LoggerFactory.getLogger(WebRTCSignalingController.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WebRTCSignalingController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/webrtc/offer")
    public void handleOffer(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        if (targetDeviceId == null || targetDeviceId.isBlank()) {
            log.warn("WebRTC offer dropped: targetDeviceId is missing");
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    @MessageMapping("/webrtc/answer")
    public void handleAnswer(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        if (targetDeviceId == null || targetDeviceId.isBlank()) {
            log.warn("WebRTC answer dropped: targetDeviceId is missing");
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    @MessageMapping("/webrtc/ice-candidate")
    public void handleIceCandidate(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        if (targetDeviceId == null || targetDeviceId.isBlank()) {
            log.warn("WebRTC ICE candidate dropped: targetDeviceId is missing");
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    /**
     * Receiver -> Sender decline signal. Lets the sender stop waiting when the user
     * rejects an incoming file (instead of leaving the sender's UI hung indefinitely).
     */
    @MessageMapping("/webrtc/decline")
    public void handleDecline(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        if (targetDeviceId == null || targetDeviceId.isBlank()) {
            log.warn("WebRTC decline dropped: targetDeviceId is missing");
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }
}