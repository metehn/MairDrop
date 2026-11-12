package com.metehan.mairdrop.service;

import com.metehan.mairdrop.model.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private final Map<String, DeviceSession> devices = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();
    private final ConnectionIdentityRegistry identityRegistry;

    // The registry MUST be the application-wide bean (also used by StompAuthChannelInterceptor and
    // WebSocketEventListener), not a private copy: registration binds the CONNECT-time claim in it,
    // and the outbound delivery guard reads ownership back from it. The explicit @Autowired keeps
    // the wiring unambiguous should another constructor ever be added.
    @Autowired
    public DeviceService(ConnectionIdentityRegistry identityRegistry) {
        this.identityRegistry = identityRegistry;
    }

    public boolean registerDevice(String deviceId, String sessionId, String group) {
        return registerDevice(deviceId, null, sessionId, group);
    }

    /**
     * Registers (or re-registers) a device, binding the client-supplied ownership token to it.
     * A device id is broadcast to every peer on the network, so anyone could send /app/register
     * with someone else's id; the token — a secret the owning browser keeps to itself — prevents
     * a second party from hijacking an existing device id. Re-registration is only accepted when
     * the presented token matches the one already bound to that id (or none was bound yet, e.g.
     * a first registration or a tokenless test/legacy client).
     *
     * <p>Additionally the CONNECT-time ownership claim for the device must still be live and owned
     * by the registering session (same token): if it expired before this register arrived, or a
     * newer session claimed the id, the registration is rejected so a device never ends up without
     * a bound claim (see {@link ConnectionIdentityRegistry#markBound}).
     *
     * @return true if the registration was accepted, false if it was rejected as a takeover attempt
     * or because no live claim was held by this session.
     */
    public boolean registerDevice(String deviceId, String token, String sessionId, String group) {
        DeviceSession existing = devices.get(deviceId);
        if (existing != null && existing.getToken() != null
                && !existing.getToken().equals(token)) {
            log.warn("Registration rejected for {}: ownership token mismatch (possible id takeover)", deviceId);
            return false;
        }

        // The CONNECT-time claim must still be live and owned by THIS session with the same token.
        // If it expired (no register within the TTL) or a newer session claimed the id, accepting
        // the registration would leave a device with no bound claim: it would keep acting while
        // another session (or nobody) receives its topics, and its id could be taken over.
        if (!identityRegistry.markBound(deviceId, sessionId, token)) {
            log.warn("Registration rejected for {}: no live CONNECT-time claim owned by session {} "
                    + "(claim expired, superseded, or token mismatch)", deviceId, sessionId);
            return false;
        }

        // Clean up stale session mapping when the same device re-registers
        if (existing != null && existing.getSessionId() != null) {
            sessionToDevice.remove(existing.getSessionId());
        }

        DeviceSession session = new DeviceSession(deviceId, sessionId, group);
        session.setToken(token != null ? token : (existing != null ? existing.getToken() : null));
        if (existing != null) {
            // Carry visibility state across a reconnect/refresh so the device does not flash back
            // into the network list (or lose its hidden-from-room state) for a broadcast window.
            session.setHidden(existing.isHidden());
            session.setPendingRoomCode(existing.getPendingRoomCode());
        }
        devices.put(deviceId, session);
        if (sessionId != null) sessionToDevice.put(sessionId, deviceId);
        log.info("Device Registered: {} (Group: {}, Session: {})", deviceId, group, sessionId);
        return true;
    }

    /**
     * Unregisters a device only when the disconnecting session matches the currently registered
     * session, preventing a stale disconnect event from removing a device that has already
     * reconnected with a new session.
     *
     * @return true if the device was actually removed; false when nothing was removed because the
     * device was unknown or the disconnecting session is stale (the device already reconnected on a
     * new session). Callers use this to avoid acting — e.g. tearing down room membership — on a
     * disconnect that has been superseded by a live reconnection.
     */
    public boolean unregisterDevice(String deviceId, String sessionId) {
        DeviceSession session = devices.get(deviceId);
        if (session == null) {
            return false;
        }
        if (sessionId != null && !sessionId.equals(session.getSessionId())) {
            log.warn("Stale disconnect ignored for device {} (disconnected: {}, current: {})",
                    deviceId, sessionId, session.getSessionId());
            sessionToDevice.remove(sessionId);
            return false;
        }
        devices.remove(deviceId);
        if (session.getSessionId() != null) {
            sessionToDevice.remove(session.getSessionId());
        }
        log.info("Device Unregistered: {}", deviceId);
        return true;
    }

    public List<String> getActiveDevicesInGroup(String group) {
        if (group == null) {
            return List.of();
        }
        return devices.values().stream()
                .filter(d -> d.isActive() && !d.isHidden() && group.equals(d.getNetworkGroup()))
                .map(DeviceSession::getDeviceId)
                .toList();
    }

    public List<String> getAllActiveDevicesInGroup(String group) {
        if (group == null) {
            return List.of();
        }
        return devices.values().stream()
                .filter(d -> d.isActive() && group.equals(d.getNetworkGroup()))
                .map(DeviceSession::getDeviceId)
                .toList();
    }

    public String getDeviceIdBySessionId(String sId) {
        if (sId == null) return null;
        return sessionToDevice.get(sId);
    }

    public String getGroup(String deviceId) {
        DeviceSession s = devices.get(deviceId);
        return (s != null) ? s.getNetworkGroup() : null;
    }

    public void setHidden(String deviceId, boolean hidden) {
        DeviceSession s = devices.get(deviceId);
        if (s != null) s.setHidden(hidden);
    }

    public boolean isHidden(String deviceId) {
        DeviceSession s = devices.get(deviceId);
        return s != null && s.isHidden();
    }

    public void setPendingRoomCode(String deviceId, String code) {
        DeviceSession s = devices.get(deviceId);
        if (s != null) s.setPendingRoomCode(code);
    }

    public String getPendingRoomCode(String deviceId) {
        DeviceSession s = devices.get(deviceId);
        return (s != null) ? s.getPendingRoomCode() : null;
    }
}