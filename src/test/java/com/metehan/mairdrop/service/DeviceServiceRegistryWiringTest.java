package com.metehan.mairdrop.service;

import com.metehan.mairdrop.config.StompAuthChannelInterceptor;
import com.metehan.mairdrop.config.WebSocketEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Guards the production wiring of the identity registry: DeviceService must operate on the SAME
 * {@link ConnectionIdentityRegistry} bean the STOMP interceptors/listeners use. If it silently
 * received a private copy (e.g. via an unannotated no-arg constructor Spring happened to pick),
 * {@code markBound} would bind claims nobody ever checks and registered devices would lose their
 * ownership protection as soon as their CONNECT-time claim expired.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeviceServiceRegistryWiringTest {

    @Autowired
    private ConnectionIdentityRegistry registry;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private StompAuthChannelInterceptor interceptor;

    @Autowired
    private WebSocketEventListener eventListener;

    @Test
    @DisplayName("DeviceService shares the application identity registry with the auth interceptor and listener")
    void deviceServiceUsesTheSharedRegistryBean() throws Exception {
        ConnectionIdentityRegistry deviceServiceRegistry = readField(deviceService, "identityRegistry");
        ConnectionIdentityRegistry interceptorRegistry = readField(interceptor, "identityRegistry");
        ConnectionIdentityRegistry listenerRegistry = readField(eventListener, "identityRegistry");

        assertNotNull(deviceServiceRegistry, "DeviceService must hold a registry");
        assertSame(registry, deviceServiceRegistry,
                "DeviceService must use the application-wide registry bean, not a private copy");
        assertSame(deviceServiceRegistry, interceptorRegistry,
                "the auth interceptor must check the same registry DeviceService binds claims into");
        assertSame(deviceServiceRegistry, listenerRegistry,
                "the disconnect listener must release the same registry's claims");
    }

    private ConnectionIdentityRegistry readField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ConnectionIdentityRegistry) field.get(target);
    }
}
