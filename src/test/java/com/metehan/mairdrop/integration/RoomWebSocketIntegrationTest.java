package com.metehan.mairdrop.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the app's STOMP/WebSocket endpoints with real client connections (no mocks)
 * to cover device discovery, room create/join/leave, and the issue #16 regression where
 * a network-visibility broadcast must not overwrite a room member's device list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Room & visibility flows over a real STOMP/WebSocket connection")
class RoomWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<TestDevice> devices = new ArrayList<>();

    @AfterEach
    void disconnectAll() {
        for (TestDevice device : devices) {
            if (device.session != null && device.session.isConnected()) {
                device.session.disconnect();
            }
        }
        devices.clear();
    }

    @Test
    @DisplayName("two devices on the same network discover each other after registering")
    void devicesDiscoverEachOtherOnSameNetwork() throws Exception {
        TestDevice a = newDevice();
        TestDevice b = newDevice();

        a.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId)), "containing both devices");
        b.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId)), "containing both devices");
    }

    @Test
    @DisplayName("create -> join -> leave updates both devices' room state and device lists")
    void roomCreateJoinLeaveFlow() throws Exception {
        TestDevice a = newDevice();
        TestDevice b = newDevice();
        a.awaitDeviceList(l -> l.contains(a.deviceId), "initial self registration");
        b.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId)), "initial registration");

        a.send("/app/rooms/create", "");
        Map<String, Object> created = a.nextRoomEvent();
        assertEquals("ROOM_CREATED", created.get("type"));
        String roomCode = (String) created.get("roomCode");
        assertEquals(5, roomCode.length());

        b.send("/app/rooms/join", roomCode);
        Map<String, Object> joined = b.nextRoomEvent();
        assertEquals("ROOM_JOINED", joined.get("type"));
        assertEquals(roomCode, joined.get("roomCode"));

        a.awaitDeviceList(l -> l.size() == 2 && l.containsAll(List.of(a.deviceId, b.deviceId)), "room roster of [A,B]");
        b.awaitDeviceList(l -> l.size() == 2 && l.containsAll(List.of(a.deviceId, b.deviceId)), "room roster of [A,B]");

        a.send("/app/rooms/leave", "");
        assertEquals("ROOM_LEFT", a.nextRoomEvent().get("type"));

        b.awaitDeviceList(l -> l.equals(List.of(b.deviceId)), "room roster shrinks to just B after A leaves");
    }

    @Test
    @DisplayName("bug: leaving a room while hidden from it must still emit ROOM_LEFT instead of silently no-op'ing")
    void leavingRoomWhileHiddenFromItStillLeavesCleanly() throws Exception {
        TestDevice a = newDevice();
        TestDevice b = newDevice();
        a.awaitDeviceList(l -> l.contains(a.deviceId), "initial self registration");
        b.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId)), "initial registration");

        a.send("/app/rooms/create", "");
        String roomCode = (String) a.nextRoomEvent().get("roomCode");
        b.send("/app/rooms/join", roomCode);
        b.nextRoomEvent();
        a.awaitDeviceList(l -> l.size() == 2, "room roster of [A,B]");

        // A hides itself from the room (UI still shows A as "in" the room, just not visible to B)
        a.send("/app/visibility/room/hide", "");
        assertEquals("ROOM_HIDDEN", a.nextVisibilityEvent().get("type"));

        // A now tries to actually leave the room it still believes it's part of
        a.send("/app/rooms/leave", "");
        Map<String, Object> leftEvent = a.nextRoomEvent();
        assertEquals("ROOM_LEFT", leftEvent.get("type"));
    }

    @Test
    @DisplayName("regression (issue #16): another device's network-visibility toggle must not overwrite room members' device list")
    void networkVisibilityChangeDoesNotOverwriteRoomDeviceList() throws Exception {
        TestDevice a = newDevice();
        TestDevice b = newDevice();
        TestDevice c = newDevice();
        // Drain every device's queue up to the point all three know about each other, so a stale
        // registration broadcast from C joining isn't mistaken later for a post-visibility-toggle message.
        a.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId, c.deviceId)), "initial registration of all three devices");
        b.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId, c.deviceId)), "initial registration of all three devices");
        c.awaitDeviceList(l -> l.containsAll(List.of(a.deviceId, b.deviceId, c.deviceId)), "initial registration of all three devices");

        a.send("/app/rooms/create", "");
        String roomCode = (String) a.nextRoomEvent().get("roomCode");
        b.send("/app/rooms/join", roomCode);
        b.nextRoomEvent();

        a.awaitDeviceList(l -> l.size() == 2 && l.containsAll(List.of(a.deviceId, b.deviceId)), "room roster of [A,B]");
        b.awaitDeviceList(l -> l.size() == 2 && l.containsAll(List.of(a.deviceId, b.deviceId)), "room roster of [A,B]");

        // C is not in the room and toggles its own network visibility
        c.send("/app/visibility/network/hide", "");
        assertEquals("NETWORK_HIDDEN", c.nextVisibilityEvent().get("type"));

        // A and B must NOT receive a network-group broadcast that overwrites their room view
        assertNull(a.pollNextDeviceList(1, TimeUnit.SECONDS),
                "Room member A's device list must not be overwritten by an unrelated network broadcast");
        assertNull(b.pollNextDeviceList(1, TimeUnit.SECONDS),
                "Room member B's device list must not be overwritten by an unrelated network broadcast");
    }

    private TestDevice newDevice() throws Exception {
        TestDevice device = new TestDevice(objectMapper);
        device.connectAndRegister(port);
        devices.add(device);
        return device;
    }

    /** A real STOMP client standing in for one browser tab / physical device. */
    private static final class TestDevice {
        final String deviceId = "dev_" + UUID.randomUUID().toString().substring(0, 8);
        private final ObjectMapper objectMapper;
        private final BlockingQueue<String> deviceListFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> roomEventFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> visibilityEventFrames = new LinkedBlockingQueue<>();
        private StompSession session;

        TestDevice(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        void connectAndRegister(int port) throws Exception {
            WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
            // String payloads (outbound, e.g. /app/register) need StringMessageConverter. Broadcasts
            // (inbound) are JSON-encoded with content-type application/json; rawByteArrayConverter()
            // ignores content-type entirely so the raw bytes always come through for manual JSON parsing.
            stompClient.setMessageConverter(new CompositeMessageConverter(
                    List.of(new StringMessageConverter(), rawByteArrayConverter())));

            String url = "ws://127.0.0.1:" + port + "/ws/websocket";
            session = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {})
                    .get(5, TimeUnit.SECONDS);

            session.subscribe("/topic/devices/" + deviceId, frameHandler(deviceListFrames));
            session.subscribe("/topic/room/" + deviceId, frameHandler(roomEventFrames));
            session.subscribe("/topic/visibility/" + deviceId, frameHandler(visibilityEventFrames));

            session.send("/app/register", deviceId);
        }

        void send(String destination, String payload) {
            session.send(destination, payload);
        }

        /** Polls device-list frames until one matches, discarding stale intermediate broadcasts. */
        List<String> awaitDeviceList(Predicate<List<String>> matches, String description) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> last = null;
            while (System.nanoTime() < deadline) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) break;
                String frame = deviceListFrames.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (frame == null) break;
                last = parseDeviceList(frame);
                if (matches.test(last)) return last;
            }
            return fail(deviceId + ": timed out waiting for device list " + description + " (last seen: " + last + ")");
        }

        List<String> pollNextDeviceList(long timeout, TimeUnit unit) throws Exception {
            String frame = deviceListFrames.poll(timeout, unit);
            return frame == null ? null : parseDeviceList(frame);
        }

        Map<String, Object> nextRoomEvent() throws Exception {
            String frame = roomEventFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, deviceId + ": timed out waiting for a room event");
            return objectMapper.readValue(frame, new TypeReference<>() {});
        }

        Map<String, Object> nextVisibilityEvent() throws Exception {
            String frame = visibilityEventFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, deviceId + ": timed out waiting for a visibility event");
            return objectMapper.readValue(frame, new TypeReference<>() {});
        }

        private List<String> parseDeviceList(String frame) throws Exception {
            return objectMapper.readValue(frame, new TypeReference<>() {});
        }

        /** ByteArrayMessageConverter only declares support for application/octet-stream by default,
         * which rejects our application/json broadcasts. Overriding supportsMimeType makes it a pure
         * passthrough so the raw frame bytes are always handed back regardless of content-type. */
        private static ByteArrayMessageConverter rawByteArrayConverter() {
            return new ByteArrayMessageConverter() {
                @Override
                protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
                    return true;
                }
            };
        }

        private static StompFrameHandler frameHandler(BlockingQueue<String> queue) {
            return new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                }
            };
        }
    }
}
