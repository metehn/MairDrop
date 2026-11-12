package com.metehan.mairdrop.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomTest {

    @Test
    @DisplayName("Room: stores code and starts with empty device list")
    void shouldStoreCodeAndStartEmpty() {
        Room room = new Room("ABCDE");

        assertEquals("ABCDE", room.getCode());
        assertTrue(room.getDeviceIds().isEmpty());
        assertNull(room.getCloseTimer());
    }

    @Test
    @DisplayName("Room: allows adding and removing device IDs")
    void shouldAllowDeviceOperations() {
        Room room = new Room("ABCDE");

        room.getDeviceIds().add("dev1");
        room.getDeviceIds().add("dev2");

        assertTrue(room.getDeviceIds().contains("dev1"));
        assertTrue(room.getDeviceIds().contains("dev2"));

        room.getDeviceIds().remove("dev1");
        assertFalse(room.getDeviceIds().contains("dev1"));
    }

    @Test
    @DisplayName("Room: setCloseTimer and getCloseTimer work correctly")
    void shouldSetAndGetCloseTimer() {
        Room room = new Room("ABCDE");

        assertNull(room.getCloseTimer());
        room.setCloseTimer(null);
        assertNull(room.getCloseTimer());
    }
}
