package com.metehan.mairdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceServiceTest {

    private ConnectionIdentityRegistry registry;
    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        registry = new ConnectionIdentityRegistry();
        deviceService = new DeviceService(registry);
    }

    private void claim(String sessionId, String deviceId, String token) {
        assertTrue(registry.claim(sessionId, deviceId, token), "test setup: claim must succeed");
    }

    @Test
    @DisplayName("Case 1: Register device with valid session")
    void shouldRegisterDeviceWithSession() {
        claim("sess1", "dev1", null);
        assertTrue(deviceService.registerDevice("dev1", "sess1", "groupA"));

        assertEquals("dev1", deviceService.getDeviceIdBySessionId("sess1"));
        assertEquals("groupA", deviceService.getGroup("dev1"));
    }

    @Test
    @DisplayName("Case 2: Register device with null session (Branch coverage for registerDevice if)")
    void shouldRegisterDeviceWithNullSession() {
        claim(null, "dev_null", null);
        assertTrue(deviceService.registerDevice("dev_null", null, "groupA"));

        assertNull(deviceService.getDeviceIdBySessionId(null));
        assertEquals("groupA", deviceService.getGroup("dev_null"));
    }

    @Test
    @DisplayName("Case 3: Unregister existing device with matching session")
    void shouldUnregisterExistingDevice() {
        claim("sess1", "dev1", null);
        deviceService.registerDevice("dev1", "sess1", "groupA");

        deviceService.unregisterDevice("dev1", "sess1");

        assertNull(deviceService.getDeviceIdBySessionId("sess1"));
        assertTrue(deviceService.getActiveDevicesInGroup("groupA").isEmpty());
    }

    @Test
    @DisplayName("Case 4: Unregister device registered with null session")
    void shouldUnregisterDeviceWithNullSession() {
        claim(null, "dev_no_sess", null);
        deviceService.registerDevice("dev_no_sess", null, "groupA");

        assertDoesNotThrow(() -> deviceService.unregisterDevice("dev_no_sess", null));
        assertTrue(deviceService.getActiveDevicesInGroup("groupA").isEmpty());
    }

    @Test
    @DisplayName("Case 5: Unregister non-existent device should not throw")
    void shouldHandleUnregisteringNonExistentDevice() {
        assertDoesNotThrow(() -> deviceService.unregisterDevice("ghost_id", "some_session"));
    }

    @Test
    @DisplayName("Case 6: Filter active devices in group (Stream filtering logic)")
    void shouldFilterActiveDevicesOnly() {
        claim("s1", "active1", null);
        claim("s2", "active2", null);
        claim("s3", "otherGroup", null);
        claim("s4", "inactive", null);
        deviceService.registerDevice("active1", "s1", "groupX");
        deviceService.registerDevice("active2", "s2", "groupX");
        deviceService.registerDevice("otherGroup", "s3", "groupY");
        deviceService.registerDevice("inactive", "s4", "groupX");

        deviceService.unregisterDevice("inactive", "s4");

        List<String> results = deviceService.getActiveDevicesInGroup("groupX");

        assertEquals(2, results.size());
        assertTrue(results.contains("active1"));
        assertTrue(results.contains("active2"));
        assertFalse(results.contains("inactive"));
        assertFalse(results.contains("otherGroup"));
    }

    @Test
    @DisplayName("Case 7: Get group for non-existent device (Ternary operator coverage)")
    void shouldReturnNullGroupForMissingDevice() {
        assertNull(deviceService.getGroup("unknown"));
    }

    @Test
    @DisplayName("Case 8: Get device ID by non-existent session")
    void shouldReturnNullForMissingSession() {
        assertNull(deviceService.getDeviceIdBySessionId("missing_session"));
    }

    @Test
    @DisplayName("Case 9: Re-registration cleans up stale session mapping")
    void shouldCleanStaleSessionOnReRegister() {
        claim("old-sess", "dev1", null);
        claim("new-sess", "dev1", null);
        deviceService.registerDevice("dev1", "old-sess", "groupA");
        deviceService.registerDevice("dev1", "new-sess", "groupA");

        assertNull(deviceService.getDeviceIdBySessionId("old-sess"),
                "Old session should be removed after re-registration");
        assertEquals("dev1", deviceService.getDeviceIdBySessionId("new-sess"));
    }

    @Test
    @DisplayName("Case 9b: getActiveDevicesInGroup(null) returns empty list (no NPE)")
    void shouldReturnEmptyListForNullGroup() {
        claim("sess1", "dev1", null);
        deviceService.registerDevice("dev1", "sess1", "groupA");

        assertTrue(deviceService.getActiveDevicesInGroup(null).isEmpty());
    }

    @Test
    @DisplayName("Case 10: Stale disconnect should not remove device with new session")
    void shouldIgnoreStaleDisconnectWhenSessionMismatch() {
        claim("new-sess", "dev1", null);
        deviceService.registerDevice("dev1", "new-sess", "groupA");

        deviceService.unregisterDevice("dev1", "old-sess");

        assertEquals("dev1", deviceService.getDeviceIdBySessionId("new-sess"),
                "Device with new session must not be removed by stale disconnect");
        assertFalse(deviceService.getActiveDevicesInGroup("groupA").isEmpty());
    }

    @Test
    @DisplayName("Case 11: unregisterDevice returns true only when it actually removes the device")
    void unregisterReturnValueReflectsRemoval() {
        claim("sess1", "dev1", null);
        deviceService.registerDevice("dev1", "sess1", "groupA");

        assertTrue(deviceService.unregisterDevice("dev1", "sess1"));
        assertFalse(deviceService.unregisterDevice("dev1", "sess1"), "already gone");
        assertFalse(deviceService.unregisterDevice("ghost", "sX"), "never existed");
    }

    @Test
    @DisplayName("Case 12: unregister with a stale session returns false and keeps the device")
    void unregisterReturnsFalseForStaleSession() {
        claim("new-sess", "dev1", null);
        deviceService.registerDevice("dev1", "new-sess", "groupA");

        assertFalse(deviceService.unregisterDevice("dev1", "old-sess"));
        assertEquals("dev1", deviceService.getDeviceIdBySessionId("new-sess"));
    }

    @Test
    @DisplayName("Case 13: re-registering an existing id with the wrong token is rejected (id takeover)")
    void shouldRejectRegistrationWithMismatchedToken() {
        claim("sess1", "dev1", "owner-token");
        assertTrue(deviceService.registerDevice("dev1", "owner-token", "sess1", "groupA"));

        // The attacker's CONNECT claim would already be refused; its register is refused too.
        assertFalse(registry.claim("sess2", "dev1", "attacker-token"));
        boolean accepted = deviceService.registerDevice("dev1", "attacker-token", "sess2", "groupA");

        assertFalse(accepted, "A different token must not be allowed to take over the device id");
        assertEquals("dev1", deviceService.getDeviceIdBySessionId("sess1"),
                "The legitimate owner's session must survive the hijack attempt");
        assertNull(deviceService.getDeviceIdBySessionId("sess2"));
    }

    @Test
    @DisplayName("Case 14: re-registering with the matching token (legit reconnect) is accepted")
    void shouldAcceptReconnectWithMatchingToken() {
        claim("sess1", "dev1", "owner-token");
        deviceService.registerDevice("dev1", "owner-token", "sess1", "groupA");

        claim("sess2", "dev1", "owner-token");
        boolean accepted = deviceService.registerDevice("dev1", "owner-token", "sess2", "groupA");

        assertTrue(accepted);
        assertNull(deviceService.getDeviceIdBySessionId("sess1"), "old session cleaned up");
        assertEquals("dev1", deviceService.getDeviceIdBySessionId("sess2"));
    }

    @Test
    @DisplayName("Case 15: visibility state (hidden / pending room) survives a reconnect")
    void shouldPreserveHiddenAndPendingRoomAcrossReRegister() {
        claim("sess1", "dev1", "owner-token");
        deviceService.registerDevice("dev1", "owner-token", "sess1", "groupA");
        deviceService.setHidden("dev1", true);
        deviceService.setPendingRoomCode("dev1", "ABCDE");

        claim("sess2", "dev1", "owner-token");
        deviceService.registerDevice("dev1", "owner-token", "sess2", "groupA");

        assertTrue(deviceService.getActiveDevicesInGroup("groupA").isEmpty(),
                "hidden device must not reappear in the visible list after reconnect");
        assertEquals("ABCDE", deviceService.getPendingRoomCode("dev1"));
    }

    private static class TestClock extends Clock {
        private Instant instant;
        TestClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = this.instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    @DisplayName("Case 16: a successful registration binds the CONNECT-time claim so it survives the TTL")
    void shouldBindClaimOnRegistration() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);
        DeviceService bound = new DeviceService(ttlRegistry);

        assertTrue(ttlRegistry.claim("sess1", "dev1", "owner-token"));
        assertTrue(bound.registerDevice("dev1", "owner-token", "sess1", "groupA"));

        clock.advance(Duration.ofHours(1));

        assertFalse(ttlRegistry.claim("sess2", "dev1", "attacker-token"),
                "a registered (bound) claim must not expire");
        assertTrue(ttlRegistry.claim("sess3", "dev1", "owner-token"), "owner may still reconnect");
    }

    @Test
    @DisplayName("Case 17: a register whose token differs from the CONNECT claim is rejected")
    void shouldRejectRegistrationWhenTokenDiffersFromConnectClaim() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);
        DeviceService bound = new DeviceService(ttlRegistry);

        assertTrue(ttlRegistry.claim("sess1", "dev1", "connect-token"));
        // The /app/register token differs from the CONNECT token: the registration must be refused
        // instead of leaving a device registered under an unbound (expiring) claim.
        assertFalse(bound.registerDevice("dev1", "other-token", "sess1", "groupA"));
        assertNull(bound.getDeviceIdBySessionId("sess1"),
                "a rejected registration must not register the device");

        clock.advance(Duration.ofMillis(150));

        assertTrue(ttlRegistry.claim("sess2", "dev1", "victim-token"),
                "the refused register must not have bound the claim");
    }

    @Test
    @DisplayName("Case 18: the victim reclaims its id after an attacker's unregistered claim expires")
    void shouldReclaimAfterAttackerClaimExpires() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);
        DeviceService bound = new DeviceService(ttlRegistry);

        // The victim is offline; the attacker CONNECTs with the victim's public id + its own token
        // but never completes /app/register.
        assertTrue(ttlRegistry.claim("attacker-sess", "dev1", "attacker-token"));
        clock.advance(Duration.ofMillis(150));

        // The lapsed claim is evicted, so the victim's CONNECT and register both succeed.
        assertTrue(ttlRegistry.claim("victim-sess", "dev1", "victim-token"));
        assertTrue(bound.registerDevice("dev1", "victim-token", "victim-sess", "groupA"));
        assertEquals("dev1", bound.getDeviceIdBySessionId("victim-sess"));
    }

    @Test
    @DisplayName("Case 19: a register arriving after the claim expired is rejected (no unbound device)")
    void shouldRejectRegistrationAfterClaimExpired() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);
        DeviceService bound = new DeviceService(ttlRegistry);

        assertTrue(ttlRegistry.claim("sess1", "dev1", "owner-token"));
        clock.advance(Duration.ofMillis(150));

        assertFalse(bound.registerDevice("dev1", "owner-token", "sess1", "groupA"),
                "a register after the claim lapsed must be rejected");
        assertNull(bound.getDeviceIdBySessionId("sess1"),
                "the device must not end up registered without a bound claim");
    }

    @Test
    @DisplayName("Case 20: a register from a session that never claimed the id is rejected")
    void shouldRejectRegistrationWithoutConnectClaim() {
        assertFalse(deviceService.registerDevice("dev1", "owner-token", "sess1", "groupA"),
                "no CONNECT-time claim exists, so the registration must be refused");
        assertNull(deviceService.getDeviceIdBySessionId("sess1"));
    }

    @Test
    @DisplayName("Case 21: a register is rejected once a newer same-token session claimed the id")
    void shouldRejectRegistrationFromSupersededSession() {
        claim("sess1", "dev1", "owner-token");
        claim("sess2", "dev1", "owner-token"); // newer session takes over the claim

        assertFalse(deviceService.registerDevice("dev1", "owner-token", "sess1", "groupA"),
                "the superseded session must not be able to register under the id");
        assertNull(deviceService.getDeviceIdBySessionId("sess1"));
    }
}
