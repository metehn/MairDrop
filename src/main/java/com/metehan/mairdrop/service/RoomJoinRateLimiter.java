package com.metehan.mairdrop.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Throttles failed room-join attempts per client so the 5-letter room code space cannot be
 * brute-forced. Room codes are only ~11.9M combinations and {@code /app/rooms/join} otherwise
 * answers every guess with ROOM_JOINED or ROOM_INVALID — a free oracle. This caps the number of
 * failed guesses allowed from one key within a sliding time window; successful joins are not counted,
 * so a legitimate user mistyping a code a few times is unaffected.
 */
@Component
public class RoomJoinRateLimiter {

    private static final int DEFAULT_MAX_FAILURES = 10;
    private static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int maxFailures;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Deque<Long>> failuresByKey = new ConcurrentHashMap<>();

    public RoomJoinRateLimiter() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW_MS, Clock.systemUTC());
    }

    RoomJoinRateLimiter(int maxFailures, long windowMs, Clock clock) {
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** @return true if {@code key} may attempt a join now, false once it has too many recent failures. */
    public synchronized boolean isAllowed(String key) {
        Deque<Long> failures = failuresByKey.get(key);
        if (failures == null) {
            return true;
        }
        pruneExpired(failures, clock.millis());
        if (failures.isEmpty()) {
            failuresByKey.remove(key);
            return true;
        }
        return failures.size() < maxFailures;
    }

    /** Records one failed join attempt for {@code key}. */
    public synchronized void recordFailure(String key) {
        long now = clock.millis();
        Deque<Long> failures = failuresByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        pruneExpired(failures, now);
        failures.addLast(now);
    }

    /** Evicts keys whose most recent failure is older than the window. Runs every 10 minutes. */
    @Scheduled(fixedRate = 600_000)
    public synchronized void evictStaleEntries() {
        long now = clock.millis();
        failuresByKey.entrySet().removeIf(entry -> {
            pruneExpired(entry.getValue(), now);
            return entry.getValue().isEmpty();
        });
    }

    private void pruneExpired(Deque<Long> failures, long now) {
        while (!failures.isEmpty() && now - failures.peekFirst() > windowMs) {
            failures.pollFirst();
        }
    }
}
