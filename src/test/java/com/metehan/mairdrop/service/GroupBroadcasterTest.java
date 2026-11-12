package com.metehan.mairdrop.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupBroadcasterTest {

    @Mock private DeviceService deviceService;
    @Mock private RoomService roomService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private GroupBroadcaster groupBroadcaster;

    private final String group = "LOCAL_NETWORK";

    @Test
    @DisplayName("null group is a no-op")
    void shouldDoNothingForNullGroup() {
        groupBroadcaster.broadcastGroup(null);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("sends the visible list to every active group member")
    void shouldBroadcastVisibleListToAllMembers() {
        List<String> visible = List.of("dev1", "dev2");
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(visible);
        when(deviceService.getAllActiveDevicesInGroup(group)).thenReturn(List.of("dev1", "dev2"));

        groupBroadcaster.broadcastGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/dev1", visible);
        verify(messagingTemplate).convertAndSend("/topic/devices/dev2", visible);
    }

    @Test
    @DisplayName("a hidden-from-network device still receives the list (it stays on the page)")
    void shouldStillNotifyHiddenDevice() {
        List<String> visible = List.of("dev2"); // dev1 hidden, excluded from the list payload
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(visible);
        when(deviceService.getAllActiveDevicesInGroup(group)).thenReturn(List.of("dev1", "dev2"));

        groupBroadcaster.broadcastGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/dev1", visible);
        verify(messagingTemplate).convertAndSend("/topic/devices/dev2", visible);
    }

    @Test
    @DisplayName("regression (issue #16): a network broadcast must NOT overwrite a room member's view")
    void shouldSkipDeviceInRoom() {
        List<String> visible = List.of("dev1", "dev2");
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(visible);
        when(deviceService.getAllActiveDevicesInGroup(group)).thenReturn(List.of("dev1", "dev2"));
        lenient().when(roomService.getRoomCode("dev2")).thenReturn("ABCDE"); // dev2 is in a room

        groupBroadcaster.broadcastGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/dev1", visible);
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/devices/dev2"), anyList());
    }

    @Test
    @DisplayName("a hidden-from-room (pending) device STILL receives updates — it shows the network view")
    void shouldNotSkipDeviceWithPendingRoom() {
        List<String> visible = List.of("dev1", "dev2");
        when(deviceService.getActiveDevicesInGroup(group)).thenReturn(visible);
        when(deviceService.getAllActiveDevicesInGroup(group)).thenReturn(List.of("dev1", "dev2"));
        // dev2 hid from its room (pending). Having left the room it displays the network list, so a
        // network broadcast must reach it — otherwise its list goes stale until it rejoins.

        groupBroadcaster.broadcastGroup(group);

        verify(messagingTemplate).convertAndSend("/topic/devices/dev1", visible);
        verify(messagingTemplate).convertAndSend("/topic/devices/dev2", visible);
    }
}
