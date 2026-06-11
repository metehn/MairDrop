package com.metehan.mairdrop.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class CommonConstantsTest {

    @Test
    void constantsShouldHaveCorrectValues() {
        assertEquals("NETWORK_GROUP", CommonConstants.NETWORK_GROUP);
        assertEquals("X-Forwarded-For", CommonConstants.X_FORWARDED_FOR);
        assertEquals("LOCAL_NETWORK", CommonConstants.LOCAL_NETWORK);
    }

    @Test
    void constantsShouldBeStaticAndAccessible() {
        assertNotNull(CommonConstants.NETWORK_GROUP);
        assertNotNull(CommonConstants.X_FORWARDED_FOR);
        assertNotNull(CommonConstants.LOCAL_NETWORK);
    }

    @Test
    void utilityClassShouldNotBeInstantiable() throws NoSuchMethodException {
        Constructor<CommonConstants> ctor = CommonConstants.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "Constructor of utility class must be private");

        ctor.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                ctor.newInstance();
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
