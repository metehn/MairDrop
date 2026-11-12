package com.metehan.mairdrop.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which ownership token currently holds each device id across live WebSocket sessions.
 *
 * <p>A device id is the public routing address peers use to reach each other, so it cannot be
 * secret. Ownership of that id — and therefore the right to subscribe to its private topics — is
 * proven with a token the owning browser keeps to itself. This registry enforces one token per
 * device id at connect time: a session presenting a different token for an id another live session
 * already holds is refused, which stops an attacker from binding a victim's id to eavesdrop on it.
 *
 * <p>To keep a CONNECT-time claim from locking a device out forever when the connecting client
 * never follows through with {@code /app/register}, a claim only reserves the id for a short
 * window. A successful registration {@link #markBound(String, String, String) binds} the claim,
 * which then lives as long as its sessions do; an unbound claim that outlives its TTL is evicted
 * (lazily on access, plus a periodic sweep) and the id becomes claimable by a new token again.
 *
 * <p>The registry also records which live session currently owns each id ({@link
 * #getOwnerSessionId(String)}). Ownership moves to the newest claimant, so when a device
 * reconnects on a new session with the same token the previous session is superseded: its existing
 * subscriptions stop receiving this id's private topics (see the outbound delivery guard) and its
 * further SUBSCRIBE/SEND frames are refused until it reconnects and claims again.
 */
@Component
public class ConnectionIdentityRegistry {

    static final long DEFAULT_CLAIM_TTL_MS = 30_000L;

    private final long claimTtlMs;
    private final Clock clock;
    private final Map<String, Claim> deviceIdToClaim = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToDeviceId = new ConcurrentHashMap<>();

    public ConnectionIdentityRegistry() {
        this(DEFAULT_CLAIM_TTL_MS, Clock.systemUTC());
    }

    ConnectionIdentityRegistry(long claimTtlMs, Clock clock) {
        this.claimTtlMs = claimTtlMs;
        this.clock = clock;
    }

    /**
     * Attempts to bind {@code deviceId} (with {@code token}) to {@code sessionId}.
     *
     * @return true if the id is unclaimed, already held by the same token (a legitimate
     * reconnect / refresh), or its previous unbound claim expired; false if a different token
     * holds a live claim for it (a takeover attempt).
     */
    public synchronized boolean claim(String sessionId, String deviceId, String token) {
        // A null token (a legacy/tokenless client) is normalised to "" so the map — which forbids
        // null values — can still track it; two tokenless claims for the same id then agree.
        String normalized = (token != null) ? token : "";
        Claim current = deviceIdToClaim.get(deviceId);
        if (current != null && current.isExpired(clock.millis(), claimTtlMs)) {
            // The previous claimer never registered, so its reservation has lapsed: evict it
            // (lazy eviction on access) and let this token take the id.
            deviceIdToClaim.remove(deviceId);
            current = null;
        }
        if (current != null && !current.token.equals(normalized)) {
            return false;
        }
        if (current == null) {
            // A fresh unbound claim; the TTL is NOT refreshed on later same-token claims so a
            // client cannot hold a victim's id open by reconnecting without ever registering.
            deviceIdToClaim.put(deviceId, new Claim(normalized, clock.millis(), sessionId));
        } else {
            // A same-token re-claim is a new tentative hold (reconnect/refresh while the old
            // session lingers): the newest session becomes the owner and its own TTL starts, so a
            // claimant that never registers cannot keep the id locked away.
            current.ownerSessionId = sessionId;
            current.claimedAtMs = clock.millis();
            current.bound = false;
        }
        if (sessionId != null) {
            sessionToDeviceId.put(sessionId, deviceId);
        }
        return true;
    }

    /**
     * Atomically marks the claim for {@code deviceId} as bound after a successful
     * {@code /app/register}, so it no longer expires while its session lives.
     *
     * @return true when a live claim exists, was made by {@code sessionId} (the current owner) and
     * carries the same token; false when the claim is missing, expired, session-mismatched or
     * token-mismatched — the caller must then reject the registration.
     */
    public synchronized boolean markBound(String deviceId, String sessionId, String token) {
        String normalized = (token != null) ? token : "";
        Claim claim = deviceIdToClaim.get(deviceId);
        if (claim == null) {
            return false;
        }
        if (claim.isExpired(clock.millis(), claimTtlMs)) {
            // The registration arrived after the CONNECT-time claim lapsed: the claim is gone, so
            // accepting the registration would leave a device with no bound claim (and no
            // protection against a takeover of its id). Evict and reject.
            deviceIdToClaim.remove(deviceId);
            return false;
        }
        if (!claim.token.equals(normalized)) {
            return false;
        }
        if (!Objects.equals(sessionId, claim.ownerSessionId)) {
            return false;
        }
        claim.bound = true;
        claim.boundSessionId = sessionId;
        return true;
    }

    /**
     * Returns the session id that currently owns {@code deviceId} (the latest live claimant), or
     * null when the id is unclaimed or its claim expired (expired claims are evicted lazily here).
     */
    public synchronized String getOwnerSessionId(String deviceId) {
        Claim claim = deviceIdToClaim.get(deviceId);
        if (claim == null) {
            return null;
        }
        if (claim.isExpired(clock.millis(), claimTtlMs)) {
            deviceIdToClaim.remove(deviceId);
            return null;
        }
        return claim.ownerSessionId;
    }

    /**
     * Releases whatever id {@code sessionId} held. The claim itself is removed only once no live
     * session holds the id anymore; while a superseded session still lingers, the current owner is
     * kept so topic delivery stays frozen for the stale sessions. The one exception is a claim a
     * still-live session had bound: if the newest (unregistered) claimant vanishes before it
     * registers, ownership falls back to that bound session instead of freezing the id behind a
     * dead owner until the TTL evicts the claim.
     */
    public synchronized void release(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String deviceId = sessionToDeviceId.remove(sessionId);
        if (deviceId == null) {
            return;
        }
        Claim claim = deviceIdToClaim.get(deviceId);
        if (claim != null && sessionId.equals(claim.boundSessionId)) {
            claim.boundSessionId = null;
        }
        if (!sessionToDeviceId.containsValue(deviceId)) {
            deviceIdToClaim.remove(deviceId);
            return;
        }
        if (claim == null || !sessionId.equals(claim.ownerSessionId)) {
            return;
        }
        // The owner left while other sessions still hold the id. If a session that had bound this
        // id is still live, hand ownership back to it (and re-bind) so a same-token reconnect that
        // claimed the id and then vanished before registering cannot freeze the live device.
        if (!claim.bound
                && claim.boundSessionId != null
                && deviceId.equals(sessionToDeviceId.get(claim.boundSessionId))) {
            claim.ownerSessionId = claim.boundSessionId;
            claim.bound = true;
        }
    }

    /** Evicts unbound claims whose TTL has lapsed. Runs every minute to sweep ids nobody touches again. */
    @Scheduled(fixedRate = 60_000)
    public synchronized void evictExpiredClaims() {
        long now = clock.millis();
        deviceIdToClaim.entrySet().removeIf(entry -> entry.getValue().isExpired(now, claimTtlMs));
    }

    /** One token's hold on a device id: temporary until a register binds it, then session-lifetime. */
    private static final class Claim {
        final String token;
        long claimedAtMs;
        boolean bound;
        String ownerSessionId;
        /** Session that last registered this claim, retained across a same-token supersession. */
        String boundSessionId;

        Claim(String token, long claimedAtMs, String ownerSessionId) {
            this.token = token;
            this.claimedAtMs = claimedAtMs;
            this.ownerSessionId = ownerSessionId;
        }

        boolean isExpired(long nowMs, long ttlMs) {
            return !bound && nowMs - claimedAtMs > ttlMs;
        }
    }
}
