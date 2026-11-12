package com.metehan.mairdrop.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomCreateRateLimiterTest {

    private static class TestClock extends Clock {
        private Instant instant;
        TestClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = this.instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    @DisplayName("allows creations until the threshold, then blocks")
    void blocksAfterThreshold() {
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(3, 60_000L, Clock.systemUTC());

        assertTrue(limiter.tryAcquire("dev1"));
        assertTrue(limiter.tryAcquire("dev1"));
        assertTrue(limiter.tryAcquire("dev1"), "still under the limit after 2 creations");

        assertFalse(limiter.tryAcquire("dev1"), "blocked once 3 creations are recorded");
    }

    @Test
    @DisplayName("creation counts are tracked independently per device")
    void keysAreIndependent() {
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(2, 60_000L, Clock.systemUTC());

        limiter.tryAcquire("dev1");
        limiter.tryAcquire("dev1");

        assertFalse(limiter.tryAcquire("dev1"));
        assertTrue(limiter.tryAcquire("dev2"), "a different device is unaffected");
    }

    @Test
    @DisplayName("creations are allowed again once old ones age out of the window")
    void reallowsAfterWindow() {
        TestClock clock = new TestClock(Instant.now());
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(2, 100L, clock);

        limiter.tryAcquire("dev1");
        limiter.tryAcquire("dev1");
        assertFalse(limiter.tryAcquire("dev1"));

        clock.advance(Duration.ofMillis(150));

        assertTrue(limiter.tryAcquire("dev1"), "the window elapsed, so old creations no longer count");
    }

    @Test
    @DisplayName("rollback releases the reservation so a refused creation does not consume the allowance")
    void rollbackReleasesReservation() {
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(2, 60_000L, Clock.systemUTC());

        assertTrue(limiter.tryAcquire("dev1"));
        assertTrue(limiter.tryAcquire("dev1"));
        assertFalse(limiter.tryAcquire("dev1"), "limit reached");

        limiter.rollback("dev1");

        assertTrue(limiter.tryAcquire("dev1"), "the rolled-back reservation frees one slot again");
    }

    @Test
    @DisplayName("evictStaleEntries removes keys whose creations have all expired")
    void evictsStaleKeys() {
        TestClock clock = new TestClock(Instant.now());
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(10, 100L, clock);

        limiter.tryAcquire("dev1");
        limiter.tryAcquire("dev2");

        clock.advance(Duration.ofMillis(200));
        limiter.evictStaleEntries();

        assertTrue(limiter.tryAcquire("dev1"), "stale key should have been evicted");
        assertTrue(limiter.tryAcquire("dev2"), "stale key should have been evicted");
    }

    @Test
    @DisplayName("concurrent creates for the same device never exceed the per-window limit")
    void concurrentAcquiresRespectLimit() throws Exception {
        RoomCreateRateLimiter limiter = new RoomCreateRateLimiter(5, 60_000L, Clock.systemUTC());

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire("dev1")) {
                    successes.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // tryAcquire checks AND records in one atomic step, so exactly maxCreations (5) of the 10
        // racing attempts succeed — never more.
        assertEquals(5, successes.get(),
                "at most (and exactly) maxCreations concurrent creations may succeed per window");
    }
}
