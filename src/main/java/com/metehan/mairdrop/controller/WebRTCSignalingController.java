package com.metehan.mairdrop.controller;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebRTCSignalingController {

    private final SimpMessagingTemplate messagingTemplate;

    public WebRTCSignalingController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/webrtc/offer")
    public void handleOffer(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    @MessageMapping("/webrtc/answer")
    public void handleAnswer(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    @MessageMapping("/webrtc/ice-candidate")
    public void handleIceCandidate(@Payload Map<String, Object> payload) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }
}