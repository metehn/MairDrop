package com.metehan.mairdrop.service;

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
class IpHandshakeInterceptorTest {

    @InjectMocks
    private IpHandshakeInterceptor interceptor;

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
    @DisplayName("Should use remote address when X-Forwarded-For is null")
    void shouldHandleNullHeader() {
        String remoteAddr = "127.0.0.1";
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn(null);
        when(httpServletRequest.getRemoteAddr()).thenReturn(remoteAddr);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(remoteAddr, attributes.get(CommonConstants.IP_ADDRESS));
    }

    @Test
    @DisplayName("Should use remote address when X-Forwarded-For is empty")
    void shouldHandleEmptyHeader() {
        String remoteAddr = "127.0.0.1";
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn("");
        when(httpServletRequest.getRemoteAddr()).thenReturn(remoteAddr);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(remoteAddr, attributes.get(CommonConstants.IP_ADDRESS));
    }

    @Test
    @DisplayName("Should use remote address when X-Forwarded-For is 'unknown'")
    void shouldHandleUnknownHeader() {
        String remoteAddr = "127.0.0.1";
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn("unknown");
        when(httpServletRequest.getRemoteAddr()).thenReturn(remoteAddr);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals(remoteAddr, attributes.get(CommonConstants.IP_ADDRESS));
    }

    @Test
    @DisplayName("Should extract first IP when X-Forwarded-For is valid")
    void shouldExtractValidIp() {
        String headerValue = "1.1.1.1, 2.2.2.2";
        when(httpServletRequest.getHeader(CommonConstants.X_FORWARDED_FOR)).thenReturn(headerValue);

        interceptor.beforeHandshake(serverRequest, serverResponse, wsHandler, attributes);

        assertEquals("1.1.1.1", attributes.get(CommonConstants.IP_ADDRESS));
    }

    @Test
    @DisplayName("Should skip IP extraction for non-servlet requests")
    void shouldHandleNonServletRequest() {
        org.springframework.http.server.ServerHttpRequest plainRequest = mock(org.springframework.http.server.ServerHttpRequest.class);

        boolean result = interceptor.beforeHandshake(plainRequest, serverResponse, wsHandler, attributes);

        assertTrue(result);
        assertFalse(attributes.containsKey(CommonConstants.IP_ADDRESS));
    }

    @Test
    @DisplayName("AfterHandshake should be a no-op")
    void afterHandshakeCompletesSilently() {
        assertDoesNotThrow(() ->
                interceptor.afterHandshake(serverRequest, serverResponse, wsHandler, null)
        );
    }
}