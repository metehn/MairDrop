package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.util.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class WebSocketController {
    private static final Logger log = LoggerFactory.getLogger(WebSocketController.class);
    private final DeviceService deviceService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketController(DeviceService deviceService, SimpMessagingTemplate messagingTemplate) {
        this.deviceService = deviceService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/register")
    public void register(@Payload String deviceId, SimpMessageHeaderAccessor headerAccessor) {
        String group = (String) headerAccessor.getSessionAttributes().get(CommonConstants.NETWORK_GROUP);
        deviceService.registerDevice(deviceId, headerAccessor.getSessionId(), group);
        log.info("Registration request has arrived: {} -> Group: {}", deviceId, group);
        broadcastList(group);
    }

    private void broadcastList(String group) {
        List<String> devices = deviceService.getActiveDevicesInGroup(group);
        log.info("Group [{}] The updated list is being published: {}", group, devices);
        for (String id : devices) {
            messagingTemplate.convertAndSend("/topic/devices/" + id, devices);
        }
    }
}