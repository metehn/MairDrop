package com.metehan.mairdrop.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many rooms one device may create per time window. Every created room lives until its
 * 15-minute close grace elapses and triggers broadcasts on each create/join/leave, so a malicious
 * (or buggy) client could otherwise pin hundreds of rooms open and flood every peer with updates.
 * Mirrors {@link RoomJoinRateLimiter}'s sliding-window design.
 *
 * <p>Acquiring a creation permit and recording it are a single atomic step ({@link
 * #tryAcquire(String)}): a separate check-then-record pair would let concurrent creates for the
 * same device all pass the check before any of them records, blowing straight through the limit.
 */
@Component
public class RoomCreateRateLimiter {

    private static final int DEFAULT_MAX_CREATIONS = 5;
    private static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int maxCreations;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Deque<Long>> creationsByKey = new ConcurrentHashMap<>();

    public RoomCreateRateLimiter() {
        this(DEFAULT_MAX_CREATIONS, DEFAULT_WINDOW_MS, Clock.systemUTC());
    }

    RoomCreateRateLimiter(int maxCreations, long windowMs, Clock clock) {
        this.maxCreations = maxCreations;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /**
     * Atomically checks and records one room creation for {@code key}.
     *
     * @return true when the creation was reserved (and recorded) within the window; false once
     * {@code key} has already reached its per-window creation limit.
     */
    public synchronized boolean tryAcquire(String key) {
        long now = clock.millis();
        Deque<Long> creations = creationsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        pruneExpired(creations, now);
        if (creations.size() >= maxCreations) {
            return false;
        }
        creations.addLast(now);
        return true;
    }

    /**
     * Releases the reservation recorded by a successful {@link #tryAcquire(String)}. Call this when
     * the room create fails after the reservation (e.g. the global concurrent-room cap rejects it),
     * so a refused creation does not consume the device's creation allowance.
     */
    public synchronized void rollback(String key) {
        Deque<Long> creations = creationsByKey.get(key);
        if (creations == null) {
            return;
        }
        creations.pollLast();
        if (creations.isEmpty()) {
            creationsByKey.remove(key);
        }
    }

    /** Evicts keys whose most recent creation is older than the window. Runs every 10 minutes. */
    @Scheduled(fixedRate = 600_000)
    public synchronized void evictStaleEntries() {
        long now = clock.millis();
        creationsByKey.entrySet().removeIf(entry -> {
            pruneExpired(entry.getValue(), now);
            return entry.getValue().isEmpty();
        });
    }

    private void pruneExpired(Deque<Long> creations, long now) {
        while (!creations.isEmpty() && now - creations.peekFirst() > windowMs) {
            creations.pollFirst();
        }
    }
}
