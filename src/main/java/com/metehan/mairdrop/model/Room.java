package com.metehan.mairdrop.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class Room {
    private final String code;
    private final Set<String> deviceIds = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> closeTimer;

    public Room(String code) {
        this.code = code;
    }

    public String getCode() { return code; }
    public Set<String> getDeviceIds() { return deviceIds; }
    public ScheduledFuture<?> getCloseTimer() { return closeTimer; }
    public void setCloseTimer(ScheduledFuture<?> timer) { this.closeTimer = timer; }
}
