package com.metehan.mairdrop.controller;

import com.metehan.mairdrop.service.DeviceService;
import com.metehan.mairdrop.service.RoomService;
import com.metehan.mairdrop.util.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebRTCSignalingController {

    private static final Logger log = LoggerFactory.getLogger(WebRTCSignalingController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final DeviceService deviceService;
    private final RoomService roomService;

    public WebRTCSignalingController(SimpMessagingTemplate messagingTemplate,
                                     DeviceService deviceService, RoomService roomService) {
        this.messagingTemplate = messagingTemplate;
        this.deviceService = deviceService;
        this.roomService = roomService;
    }

    @MessageMapping("/webrtc/offer")
    public void handleOffer(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        String senderId = stampSender(payload, headerAccessor);
        if (targetDeviceId == null || targetDeviceId.isBlank() || senderId == null) {
            log.warn("WebRTC offer dropped: missing target or unidentified sender");
            return;
        }
        // An offer is what pops the receiver's accept dialog, so it is the gate that must be scoped:
        // only forward it when sender and target actually share a room or a visible network group.
        // This blocks unsolicited transfer requests and probing a device that hid from the network.
        if (!canReach(senderId, targetDeviceId)) {
            log.warn("WebRTC offer from {} to {} refused: no shared room or visible network group",
                    senderId, targetDeviceId);
            messagingTemplate.convertAndSend("/topic/webrtc/" + senderId,
                    Map.of("offerRefused", true, "senderDeviceId", targetDeviceId));
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    @MessageMapping("/webrtc/answer")
    public void handleAnswer(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        forwardWithinExchange(payload, headerAccessor, "answer");
    }

    @MessageMapping("/webrtc/ice-candidate")
    public void handleIceCandidate(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        forwardWithinExchange(payload, headerAccessor, "ICE candidate");
    }

    /**
     * Receiver -> Sender decline signal. Lets the sender stop waiting when the user
     * rejects an incoming file (instead of leaving the sender's UI hung indefinitely).
     */
    @MessageMapping("/webrtc/decline")
    public void handleDecline(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        forwardWithinExchange(payload, headerAccessor, "decline");
    }

    // Answer/ICE/decline belong to an exchange an already-scoped offer started; the receiving side
    // only acts on them if it has a matching connection. We still stamp the authentic sender so a
    // third party cannot spoof, say, a decline "from" a peer to abort someone else's transfer.
    private void forwardWithinExchange(Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor,
                                       String kind) {
        String targetDeviceId = (String) payload.get("targetDeviceId");
        String senderId = stampSender(payload, headerAccessor);
        if (targetDeviceId == null || targetDeviceId.isBlank() || senderId == null) {
            log.warn("WebRTC {} dropped: missing target or unidentified sender", kind);
            return;
        }
        messagingTemplate.convertAndSend("/topic/webrtc/" + targetDeviceId, payload);
    }

    // Overwrites the client-supplied senderDeviceId with the session's authenticated device id so a
    // caller cannot impersonate another device. Returns that id, or null if the session is unbound.
    private String stampSender(Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        String senderId = (attributes != null) ? (String) attributes.get(CommonConstants.SESSION_DEVICE_ID) : null;
        if (senderId != null) {
            payload.put("senderDeviceId", senderId);
        }
        return senderId;
    }

    private boolean canReach(String senderId, String targetId) {
        String senderRoom = roomService.getRoomCode(senderId);
        String targetRoom = roomService.getRoomCode(targetId);
        if (senderRoom != null && senderRoom.equals(targetRoom)) {
            return true;
        }
        String senderGroup = deviceService.getGroup(senderId);
        String targetGroup = deviceService.getGroup(targetId);
        return senderGroup != null && senderGroup.equals(targetGroup) && !deviceService.isHidden(targetId);
    }
}
