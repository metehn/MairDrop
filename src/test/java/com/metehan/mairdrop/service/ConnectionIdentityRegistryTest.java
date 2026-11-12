package com.metehan.mairdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionIdentityRegistryTest {

    private ConnectionIdentityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConnectionIdentityRegistry();
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
    @DisplayName("first claim of a device id succeeds and binds its token")
    void firstClaimSucceeds() {
        assertTrue(registry.claim("s1", "dev1", "owner-token"));
    }

    @Test
    @DisplayName("a different token cannot claim a device id already held (takeover refused)")
    void differentTokenRefused() {
        registry.claim("s1", "dev1", "owner-token");

        assertFalse(registry.claim("s2", "dev1", "attacker-token"));
    }

    @Test
    @DisplayName("the same token may re-claim its id (legitimate reconnect while the old session lingers)")
    void sameTokenReclaimAllowed() {
        registry.claim("s1", "dev1", "owner-token");

        assertTrue(registry.claim("s2", "dev1", "owner-token"));
    }

    @Test
    @DisplayName("an id becomes claimable by a new token only once every holding session has released it")
    void reclaimableAfterFullRelease() {
        registry.claim("s1", "dev1", "owner-token");
        registry.claim("s2", "dev1", "owner-token");

        registry.release("s1");
        assertFalse(registry.claim("s3", "dev1", "other-token"), "still held by s2");

        registry.release("s2");
        assertTrue(registry.claim("s3", "dev1", "other-token"), "free after the last holder left");
    }

    @Test
    @DisplayName("tokenless (legacy) claims for the same id agree with each other")
    void tokenlessClaimsAgree() {
        assertTrue(registry.claim("s1", "dev1", null));
        assertTrue(registry.claim("s2", "dev1", null));
    }

    @Test
    @DisplayName("an unregistered claim expires, so the id becomes claimable with a different token")
    void unregisteredClaimExpires() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        assertTrue(ttlRegistry.claim("s1", "dev1", "attacker-token"));
        assertFalse(ttlRegistry.claim("s2", "dev1", "victim-token"),
                "before the TTL elapses the claim still blocks a different token");

        clock.advance(Duration.ofMillis(150));

        assertTrue(ttlRegistry.claim("s2", "dev1", "victim-token"),
                "once the TTL lapsed without a register the id must be reclaimable");
    }

    @Test
    @DisplayName("a bound claim never expires, so a registered owner stays protected past the TTL")
    void boundClaimDoesNotExpire() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        assertTrue(ttlRegistry.claim("s1", "dev1", "owner-token"));
        assertTrue(ttlRegistry.markBound("dev1", "s1", "owner-token"));

        clock.advance(Duration.ofHours(1));

        assertFalse(ttlRegistry.claim("s2", "dev1", "attacker-token"), "bound claim must outlive the TTL");
        assertTrue(ttlRegistry.claim("s3", "dev1", "owner-token"), "owner may still reconnect");
    }

    @Test
    @DisplayName("evictExpiredClaims removes only unbound claims whose TTL has lapsed")
    void evictsOnlyExpiredUnboundClaims() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        ttlRegistry.claim("s1", "dev1", "owner-token");
        ttlRegistry.markBound("dev1", "s1", "owner-token");
        ttlRegistry.claim("s2", "dev2", "abandoned-token");

        clock.advance(Duration.ofMillis(150));
        ttlRegistry.evictExpiredClaims();

        assertTrue(ttlRegistry.claim("s3", "dev2", "other-token"),
                "the abandoned claim should have been evicted");
        assertFalse(ttlRegistry.claim("s4", "dev1", "attacker-token"), "bound claim must survive the sweep");
    }

    @Test
    @DisplayName("getOwnerSessionId returns the claiming session and null for an unclaimed id")
    void ownerSessionIdReflectsClaimant() {
        assertNull(registry.getOwnerSessionId("dev1"), "unclaimed id has no owner");

        registry.claim("s1", "dev1", "owner-token");

        assertEquals("s1", registry.getOwnerSessionId("dev1"));
    }

    @Test
    @DisplayName("a same-token re-claim moves ownership to the newest session")
    void sameTokenReclaimMovesOwnership() {
        registry.claim("s1", "dev1", "owner-token");
        registry.claim("s2", "dev1", "owner-token");

        assertEquals("s2", registry.getOwnerSessionId("dev1"),
                "the newest claimant is the current owner");
    }

    @Test
    @DisplayName("getOwnerSessionId evicts an expired claim lazily and returns null")
    void ownerSessionIdEvictsExpiredClaim() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        ttlRegistry.claim("s1", "dev1", "owner-token");
        clock.advance(Duration.ofMillis(150));

        assertNull(ttlRegistry.getOwnerSessionId("dev1"), "expired claim must yield no owner");
        assertTrue(ttlRegistry.claim("s2", "dev1", "other-token"),
                "the lapsed claim must have been evicted on lookup");
    }

    @Test
    @DisplayName("markBound fails when no claim exists (registration must be rejected)")
    void markBoundFailsWithoutClaim() {
        assertFalse(registry.markBound("dev1", "s1", "owner-token"));
    }

    @Test
    @DisplayName("markBound fails and evicts when the claim already expired (register racing TTL expiry)")
    void markBoundFailsAfterClaimExpired() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        ttlRegistry.claim("s1", "dev1", "owner-token");
        clock.advance(Duration.ofMillis(150));

        assertFalse(ttlRegistry.markBound("dev1", "s1", "owner-token"),
                "a register arriving after the claim lapsed must be rejected");
        assertNull(ttlRegistry.getOwnerSessionId("dev1"), "the expired claim must be gone");
        assertTrue(ttlRegistry.claim("s2", "dev1", "other-token"),
                "the id must be claimable again after the eviction");
    }

    @Test
    @DisplayName("markBound succeeds just before the TTL elapses (register wins the race) and binds")
    void markBoundSucceedsBeforeExpiry() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        ttlRegistry.claim("s1", "dev1", "owner-token");
        clock.advance(Duration.ofMillis(99));

        assertTrue(ttlRegistry.markBound("dev1", "s1", "owner-token"));

        clock.advance(Duration.ofHours(1));
        assertEquals("s1", ttlRegistry.getOwnerSessionId("dev1"),
                "a bound claim must survive the TTL");
    }

    @Test
    @DisplayName("markBound fails when the register token differs from the CONNECT claim token")
    void markBoundFailsOnTokenMismatch() {
        registry.claim("s1", "dev1", "connect-token");

        assertFalse(registry.markBound("dev1", "s1", "other-token"));
    }

    @Test
    @DisplayName("markBound fails when another session claimed the id since (session mismatch)")
    void markBoundFailsWhenSuperseded() {
        registry.claim("s1", "dev1", "owner-token");
        registry.claim("s2", "dev1", "owner-token"); // s2 is now the owner

        assertFalse(registry.markBound("dev1", "s1", "owner-token"),
                "the superseded session must not bind the claim");
        assertTrue(registry.markBound("dev1", "s2", "owner-token"),
                "the current owner's register must still bind");
    }

    @Test
    @DisplayName("releasing the owner while a same-token session lingers keeps that session superseded")
    void releaseKeepsOwnerFrozenForLingeringSupersededSession() {
        registry.claim("s1", "dev1", "owner-token");
        registry.claim("s2", "dev1", "owner-token");
        assertEquals("s2", registry.getOwnerSessionId("dev1"));

        registry.release("s2");

        assertEquals("s2", registry.getOwnerSessionId("dev1"),
                "ownership must not revert to the superseded session");
    }

    @Test
    @DisplayName("a same-token claimant that vanishes before register returns ownership to the live bound session")
    void releaseRestoresLiveBoundSessionWhenVanishingClaimantOwned() {
        TestClock clock = new TestClock(Instant.now());
        ConnectionIdentityRegistry ttlRegistry = new ConnectionIdentityRegistry(100L, clock);

        ttlRegistry.claim("s1", "dev1", "owner-token");
        assertTrue(ttlRegistry.markBound("dev1", "s1", "owner-token"));

        // A reconnect/refresh on a new session supersedes s1, then dies before registering.
        assertTrue(ttlRegistry.claim("s2", "dev1", "owner-token"));
        assertEquals("s2", ttlRegistry.getOwnerSessionId("dev1"));

        ttlRegistry.release("s2");

        assertEquals("s1", ttlRegistry.getOwnerSessionId("dev1"),
                "the live bound session must regain ownership instead of staying frozen behind a dead claimant");
        assertFalse(ttlRegistry.claim("s3", "dev1", "other-token"),
                "the restored claim must still protect the id from a different token");

        // Ownership was restored as bound again, so the claim no longer expires with s2's TTL.
        clock.advance(Duration.ofHours(1));
        assertEquals("s1", ttlRegistry.getOwnerSessionId("dev1"),
                "the restored owner must be bound again, not left to expire with the superseding claim");
    }
}
