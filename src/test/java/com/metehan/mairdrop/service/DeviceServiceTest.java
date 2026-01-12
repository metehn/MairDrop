package com.metehan.mairdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceServiceTest {

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService();
    }

    @Test
    @DisplayName("Case 1: Register device with valid session")
    void shouldRegisterDeviceWithSession() {
        deviceService.registerDevice("dev1", "sess1", "groupA");

        assertEquals("dev1", deviceService.getDeviceIdBySessionId("sess1"));
        assertEquals("groupA", deviceService.getGroup("dev1"));
    }

    @Test
    @DisplayName("Case 2: Register device with null session (Branch coverage for registerDevice if)")
    void shouldRegisterDeviceWithNullSession() {
        deviceService.registerDevice("dev_null", null, "groupA");

        assertNull(deviceService.getDeviceIdBySessionId(null));
        assertEquals("groupA", deviceService.getGroup("dev_null"));
    }

    @Test
    @DisplayName("Case 3: Unregister existing device with session (Branch coverage for unregisterDevice)")
    void shouldUnregisterExistingDevice() {
        deviceService.registerDevice("dev1", "sess1", "groupA");

        deviceService.unregisterDevice("dev1");

        assertNull(deviceService.getDeviceIdBySessionId("sess1"));
        assertTrue(deviceService.getActiveDevicesInGroup("groupA").isEmpty());
    }

    @Test
    @DisplayName("Case 4: Unregister device with null session (Internal branch coverage)")
    void shouldUnregisterDeviceWithNullSession() {
        deviceService.registerDevice("dev_no_sess", null, "groupA");

        assertDoesNotThrow(() -> deviceService.unregisterDevice("dev_no_sess"));
    }

    @Test
    @DisplayName("Case 5: Unregister non-existent device (Else branch for if(session != null))")
    void shouldHandleUnregisteringNonExistentDevice() {
        assertDoesNotThrow(() -> deviceService.unregisterDevice("ghost_id"));
    }

    @Test
    @DisplayName("Case 6: Filter active devices in group (Stream filtering logic)")
    void shouldFilterActiveDevicesOnly() {
        deviceService.registerDevice("active1", "s1", "groupX");
        deviceService.registerDevice("active2", "s2", "groupX");
        deviceService.registerDevice("otherGroup", "s3", "groupY");
        deviceService.registerDevice("inactive", "s4", "groupX");

        deviceService.unregisterDevice("inactive");

        List<String> results = deviceService.getActiveDevicesInGroup("groupX");

        assertEquals(2, results.size());
        assertTrue(results.contains("active1"));
        assertTrue(results.contains("active2"));
        assertFalse(results.contains("inactive"));
        assertFalse(results.contains("otherGroup"));
    }

    @Test
    @DisplayName("Case 7: Get group for non-existent device (Ternary operator coverage)")
    void shouldReturnNullGroupForMissingDevice() {
        assertNull(deviceService.getGroup("unknown"));
    }

    @Test
    @DisplayName("Case 8: Get device ID by non-existent session")
    void shouldReturnNullForMissingSession() {
        assertNull(deviceService.getDeviceIdBySessionId("missing_session"));
    }
}