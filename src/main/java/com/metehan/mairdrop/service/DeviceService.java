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
        devices.put(deviceId, new DeviceSession(deviceId, sessionId, group));
        if (sessionId != null) sessionToDevice.put(sessionId, deviceId);
        log.info("Device Registered: {} (Group: {}, Session: {})", deviceId, group, sessionId);
    }

    public void unregisterDevice(String deviceId) {
        DeviceSession session = devices.get(deviceId);
        if (session != null) {
            session.setActive(false);
            if (session.getSessionId() != null) sessionToDevice.remove(session.getSessionId());
            log.info("Device Registration Deleted: {}", deviceId);
        }
    }

    public List<String> getActiveDevicesInGroup(String group) {
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