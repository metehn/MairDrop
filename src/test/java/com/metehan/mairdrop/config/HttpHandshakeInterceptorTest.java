package com.metehan.mairdrop.config;

import com.metehan.mairdrop.util.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpHandshakeInterceptorTest {

    @InjectMocks
    private HttpHandshakeInterceptor interceptor;

    @Mock
    private ServletServerHttpRequest serverRequest;

    @Mock
    private HttpServletRequest httpServletRequest;

    @Mock
    private ServerHttpResponse serverResponse;

    @Mock
    private WebSocketHandler wsHandler;

    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        attributes = new HashMap<>();
        lenient().when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
    }

    @Test
    @DisplayName("Case 1: Should identify 192.168.x.x as LOCAL_NETWORK")
    void shouldIdentify192PrefixAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("192.168.1.50");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 2: Should identify 10.x.x.x as LOCAL_NETWORK")
    void shouldIdentify10PrefixAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("10.0.0.1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 3: Should identify IPv4 localhost as LOCAL_NETWORK")
    void shouldIdentifyIpv4LocalhostAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 4: Should identify IPv6 localhost as LOCAL_NETWORK")
    void shouldIdentifyIpv6LocalhostAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("0:0:0:0:0:0:0:1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 5: Should identify external IP as its own group")
    void shouldIdentifyExternalIpAsOwnGroup() {
        String externalIp = "8.8.8.8";
        when(httpServletRequest.getRemoteAddr()).thenReturn(externalIp);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(externalIp, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 6: Should handle X-Forwarded-For header (Proxy Case)")
    void shouldHandleXForwardedForHeader() {
        String proxyIp = "192.168.10.5, 8.8.8.8"; // First one should be picked
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn(proxyIp);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 7: Should handle empty X-Forwarded-For header")
    void shouldHandleEmptyForwardedHeader() {
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn("");
        when(httpServletRequest.getRemoteAddr()).thenReturn("8.8.4.4");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals("8.8.4.4", attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 8: Should do nothing for non-servlet requests")
    void shouldHandleNonServletRequest() {
        org.springframework.http.server.ServerHttpRequest plainRequest = mock(org.springframework.http.server.ServerHttpRequest.class);

        boolean result = interceptor.beforeHandshake(plainRequest, serverResponse, wsHandler, attributes);

        assertTrue(result);
        assertFalse(attributes.containsKey(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 9: Coverage for afterHandshake")
    void shouldExecuteAfterHandshakeWithoutException() {
        assertDoesNotThrow(() ->
                interceptor.afterHandshake(serverRequest, serverResponse, wsHandler, null)
        );
    }

    @Test
    @DisplayName("Case 10: Should identify 172.16.x.x as LOCAL_NETWORK (RFC 1918 172.16/12 range)")
    void shouldIdentify172RangeAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("172.16.0.5");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 11: Should identify 172.31.x.x as LOCAL_NETWORK (upper boundary of 172.16/12)")
    void shouldIdentify172UpperBoundaryAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("172.31.255.255");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 12: Should identify 172.32.x.x as external (outside 172.16/12 range)")
    void shouldIdentify172OutsideRangeAsExternal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("172.32.0.1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals("172.32.0.1", attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 13: Should identify IPv6 short-form loopback ::1 as LOCAL_NETWORK")
    void shouldIdentifyIpv6ShortLoopbackAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("::1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }

    @Test
    @DisplayName("Case 14: Should identify IPv6 link-local fe80:: as LOCAL_NETWORK")
    void shouldIdentifyIpv6LinkLocalAsLocal() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("fe80::1");

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(CommonConstants.LOCAL_NETWORK, attributes.get(CommonConstants.NETWORK_GROUP));
    }
}