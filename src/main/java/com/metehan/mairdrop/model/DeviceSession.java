package com.metehan.mairdrop.model;

public class DeviceSession {
    private String deviceId;
    private String sessionId;
    private String networkGroup;
    private boolean active;
    private long lastSeen;

    public DeviceSession(String deviceId, String sessionId, String networkGroup) {
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.networkGroup = networkGroup;
        this.active = true;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getDeviceId() { return deviceId; }
    public String getSessionId() { return sessionId; }
    public String getNetworkGroup() { return networkGroup; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) {
        this.active = active;
        if (active) this.lastSeen = System.currentTimeMillis();
    }
    public long getLastSeen(){
        return this.lastSeen;
    }
}