package com.metehan.mairdrop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommonConstantsTest {

    @Test
    void constantsShouldHaveCorrectValues() {
        assertEquals("NETWORK_GROUP", CommonConstants.NETWORK_GROUP);
        assertEquals("X-Forwarded-For", CommonConstants.X_FORWARDED_FOR);
        assertEquals("LOCAL_NETWORK", CommonConstants.LOCAL_NETWORK);
        assertEquals("IP_ADDRESS", CommonConstants.IP_ADDRESS);
    }

    @Test
    void constantsShouldBeStaticAndAccessible() {
        assertNotNull(CommonConstants.NETWORK_GROUP);
        assertNotNull(CommonConstants.IP_ADDRESS);
    }
}