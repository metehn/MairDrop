package com.metehan.mairdrop.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single owner of network-group device-list broadcasts. Routing every publish path (registration,
 * disconnect, visibility toggles) through here keeps the "skip devices that are in a room" rule in
 * one place, so a network broadcast can never overwrite a room member's room-scoped device view.
 */
@Component
public class GroupBroadcaster {

    private final DeviceService deviceService;
    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupBroadcaster(DeviceService deviceService, RoomService roomService,
                            SimpMessagingTemplate messagingTemplate) {
        this.deviceService = deviceService;
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publishes the group's visible-device list to every active member of the group, skipping only
     * devices that are currently in a public room — those display a room-scoped list a network
     * broadcast must not overwrite. A device that hid from its room shows the network list, so it is
     * still notified. Hidden-from-network devices are notified too: they stay on the page and need
     * to see the network list even while invisible to others.
     */
    public void broadcastGroup(String group) {
        if (group == null) {
            return;
        }
        List<String> visibleDevices = deviceService.getActiveDevicesInGroup(group);
        for (String id : deviceService.getAllActiveDevicesInGroup(group)) {
            // Skip only devices that are actively in a room — they display a room-scoped roster a
            // network broadcast must not overwrite. A device that hid FROM its room is showing the
            // network list, so it must keep receiving these updates or that list goes stale.
            if (roomService.getRoomCode(id) == null) {
                messagingTemplate.convertAndSend("/topic/devices/" + id, visibleDevices);
            }
        }
    }
}
