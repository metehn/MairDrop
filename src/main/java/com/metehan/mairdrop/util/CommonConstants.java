package com.metehan.mairdrop.util;

import java.util.regex.Pattern;

public final class CommonConstants {

    public static final String NETWORK_GROUP = "NETWORK_GROUP";
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String LOCAL_NETWORK = "LOCAL_NETWORK";

    // STOMP CONNECT header names carrying the client's identity, and the session-attribute key the
    // auth interceptor binds the verified device id under so later SUBSCRIBE frames can be scoped.
    public static final String HEADER_DEVICE_ID = "deviceId";
    public static final String HEADER_TOKEN = "token";
    public static final String SESSION_DEVICE_ID = "DEVICE_ID";

    // Client IP resolved at the handshake, stashed so room-join brute-force can be rate-limited per IP.
    public static final String SESSION_CLIENT_IP = "CLIENT_IP";

    // Device ids are generated as "dev_" + lowercase hex (see generateDeviceId() in
    // static/js/app.js). This is a frontend contract: the (fallback) generator MUST emit lowercase
    // hex only, and server-side tests/reconnects must do the same. Subscribing to a device-scoped
    // topic is only allowed for targets of this exact shape so an id can never smuggle Ant-style
    // pattern characters into a broker destination.
    public static final Pattern DEVICE_ID_PATTERN = Pattern.compile("dev_[0-9a-f]+");

    private CommonConstants() {
        // utility class
    }
}
