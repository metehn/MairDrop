package com.metehan.mairdrop.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceSessionTest {

    @Test
    void constructorShouldInitializeFieldsCorrectly() {
        String deviceId = "dev-123";
        String sessionId = "sess-456";
        String group = "LOCAL_NETWORK";

        DeviceSession session = new DeviceSession(deviceId, sessionId, group);

        assertEquals(deviceId, session.getDeviceId());
        assertEquals(sessionId, session.getSessionId());
        assertEquals(group, session.getNetworkGroup());
        assertTrue(session.isActive(), "The newly created session must be active.");
        assertTrue(session.getLastSeen() <= System.currentTimeMillis(), "The time `lastSeen` cannot be greater than the time `present`.");
    }

    @Test
    void shouldSetStatusToInactive() {
        DeviceSession session = new DeviceSession("d", "s", "g");
        session.setActive(false);
        assertFalse(session.isActive());
    }

    @Test
    void shouldUpdateLastSeenWhenSetToActive() throws InterruptedException {
        DeviceSession session = new DeviceSession("d", "s", "g");
        long initialLastSeen = session.getLastSeen();
        Thread.sleep(10);
        session.setActive(true);
        assertTrue(session.isActive());
        assertTrue(session.getLastSeen() > initialLastSeen, "The lastSeen value should be updated.");
    }
}