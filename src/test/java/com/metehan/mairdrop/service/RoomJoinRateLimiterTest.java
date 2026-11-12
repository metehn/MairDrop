package com.metehan.mairdrop.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomJoinRateLimiterTest {

    private static class TestClock extends Clock {
        private Instant instant;
        TestClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = this.instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    @DisplayName("allows attempts until the failure threshold, then blocks")
    void blocksAfterThreshold() {
        RoomJoinRateLimiter limiter = new RoomJoinRateLimiter(3, 60_000L, Clock.systemUTC());

        assertTrue(limiter.isAllowed("ip1"));
        limiter.recordFailure("ip1");
        limiter.recordFailure("ip1");
        assertTrue(limiter.isAllowed("ip1"), "still under the limit after 2 failures");
        limiter.recordFailure("ip1");

        assertFalse(limiter.isAllowed("ip1"), "blocked once 3 failures are recorded");
    }

    @Test
    @DisplayName("failure counts are tracked independently per key")
    void keysAreIndependent() {
        RoomJoinRateLimiter limiter = new RoomJoinRateLimiter(2, 60_000L, Clock.systemUTC());

        limiter.recordFailure("ip1");
        limiter.recordFailure("ip1");

        assertFalse(limiter.isAllowed("ip1"));
        assertTrue(limiter.isAllowed("ip2"), "a different client is unaffected");
    }

    @Test
    @DisplayName("attempts are allowed again once old failures age out of the window")
    void reallowsAfterWindow() {
        TestClock clock = new TestClock(Instant.now());
        RoomJoinRateLimiter limiter = new RoomJoinRateLimiter(2, 100L, clock);

        limiter.recordFailure("ip1");
        limiter.recordFailure("ip1");
        assertFalse(limiter.isAllowed("ip1"));

        clock.advance(Duration.ofMillis(150));

        assertTrue(limiter.isAllowed("ip1"), "the window elapsed, so old failures no longer count");
    }

    @Test
    @DisplayName("evictStaleEntries removes keys whose failures have all expired")
    void evictsStaleKeys() {
        TestClock clock = new TestClock(Instant.now());
        RoomJoinRateLimiter limiter = new RoomJoinRateLimiter(10, 100L, clock);

        limiter.recordFailure("ip1");
        limiter.recordFailure("ip2");

        clock.advance(Duration.ofMillis(200));
        limiter.evictStaleEntries();

        assertTrue(limiter.isAllowed("ip1"), "stale key should have been evicted");
        assertTrue(limiter.isAllowed("ip2"), "stale key should have been evicted");
    }

    @Test
    @DisplayName("evictStaleEntries keeps keys with recent failures")
    void keepsRecentKeys() {
        TestClock clock = new TestClock(Instant.now());
        RoomJoinRateLimiter limiter = new RoomJoinRateLimiter(2, 1000L, clock);

        limiter.recordFailure("ip1");
        limiter.recordFailure("ip1");

        clock.advance(Duration.ofMillis(50));
        limiter.evictStaleEntries();

        assertFalse(limiter.isAllowed("ip1"), "recent failures should survive eviction");
    }
}
