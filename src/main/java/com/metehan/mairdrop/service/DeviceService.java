package com.metehan.mairdrop.service;

import com.metehan.mairdrop.model.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private final Map<String, DeviceSession> devices = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();

    public void registerDevice(String deviceId, String sessionId, String group) {
        // Clean up stale session mapping when the same device re-registers
        DeviceSession existing = devices.get(deviceId);
        if (existing != null && existing.getSessionId() != null) {
            sessionToDevice.remove(existing.getSessionId());
        }
        devices.put(deviceId, new DeviceSession(deviceId, sessionId, group));
        if (sessionId != null) sessionToDevice.put(sessionId, deviceId);
        log.info("Device Registered: {} (Group: {}, Session: {})", deviceId, group, sessionId);
    }

    /**
     * Unregisters a device only when the disconnecting session matches the currently
     * registered session. This prevents a stale disconnect event from removing a device
     * that has already reconnected with a new session.
     */
    public void unregisterDevice(String deviceId, String sessionId) {
        DeviceSession session = devices.get(deviceId);
        if (session == null) {
            return;
        }
        if (sessionId != null && !sessionId.equals(session.getSessionId())) {
            log.warn("Stale disconnect ignored for device {} (disconnected: {}, current: {})",
                    deviceId, sessionId, session.getSessionId());
            sessionToDevice.remove(sessionId);
            return;
        }
        devices.remove(deviceId);
        if (session.getSessionId() != null) {
            sessionToDevice.remove(session.getSessionId());
        }
        log.info("Device Unregistered: {}", deviceId);
    }

    public List<String> getActiveDevicesInGroup(String group) {
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
}