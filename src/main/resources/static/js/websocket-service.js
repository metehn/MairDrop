let stompClient = null;
let activeSocket = null;
let reconnectTimer = null;
let registerPayload = null; // "deviceId|token" (or bare deviceId) reused for every (re)registration

const STOMP_HEARTBEAT_MS = 10000;
const RECONNECT_DELAY_MS = 3000;

const closePreviousSocket = () => {
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (stompClient && stompClient.connected) {
        try { stompClient.disconnect(() => {}); } catch (e) { /* ignore */ }
    }
    if (activeSocket) {
        try { activeSocket.close(); } catch (e) { /* ignore */ }
    }
    stompClient = null;
    activeSocket = null;
};

const SocketService = {
    connect: (deviceId, callbacks) => {
        closePreviousSocket();
        registerPayload = callbacks.token ? (deviceId + '|' + callbacks.token) : deviceId;

        activeSocket = new SockJS('/ws');
        stompClient = Stomp.over(activeSocket);
        stompClient.debug = null;
        // STOMP-level heartbeat detects dead connections without waiting for TCP timeout
        stompClient.heartbeat.outgoing = STOMP_HEARTBEAT_MS;
        stompClient.heartbeat.incoming = STOMP_HEARTBEAT_MS;

        const onConnected = () => {
            UI.updateConnectionStatus(true);

            stompClient.subscribe('/topic/devices/' + deviceId, (msg) => {
                try {
                    callbacks.onDevicesUpdate(JSON.parse(msg.body));
                } catch (e) {
                    console.warn('Bad device list payload:', e);
                }
            });

            stompClient.subscribe('/topic/webrtc/' + deviceId, (msg) => {
                try {
                    callbacks.onSignal(JSON.parse(msg.body));
                } catch (e) {
                    console.warn('Bad signal payload:', e);
                }
            });

            stompClient.subscribe('/topic/room/' + deviceId, (msg) => {
                try {
                    callbacks.onRoomEvent(JSON.parse(msg.body));
                } catch (e) {
                    console.warn('Bad room event payload:', e);
                }
            });

            stompClient.subscribe('/topic/visibility/' + deviceId, (msg) => {
                try {
                    callbacks.onVisibilityEvent(JSON.parse(msg.body));
                } catch (e) {
                    console.warn('Bad visibility event payload:', e);
                }
            });

            stompClient.send('/app/register', {}, registerPayload);

            // Rejoin any saved room BEFORE re-asserting visibility. The server processes messages
            // from one client in order (setPreserveReceiveOrder), so register → join → hide lands in
            // the right sequence; sending a room-hide before the join would no-op (not in a room yet)
            // and leave client and server disagreeing about visibility.
            // A user who hid from the room must STAY hidden after a reload: skip the auto-rejoin
            // (app.js drops the saved room_id in that case too) so they don't silently reappear
            // in the room's roster.
            const savedRoomId = sessionStorage.getItem('room_id');
            if (savedRoomId && sessionStorage.getItem('mairdrop_room_hidden') !== '1') {
                stompClient.send('/app/rooms/join', {}, savedRoomId);
            }
            if (callbacks.onRegistered) callbacks.onRegistered();
        };

        const onError = () => {
            UI.updateConnectionStatus(false);
            reconnectTimer = setTimeout(
                () => SocketService.connect(deviceId, callbacks),
                RECONNECT_DELAY_MS
            );
        };

        // Identify the connection up front so the server can bind this device id to the session
        // (proving ownership with the token) and reject any attempt to subscribe to another
        // device's topics. The token is the same secret used for registration; it is never shared
        // with peers.
        const connectHeaders = callbacks.token
            ? { deviceId: deviceId, token: callbacks.token }
            : { deviceId: deviceId };
        stompClient.connect(connectHeaders, onConnected, onError);
    },

    sendSignal: (type, data) => {
        if (stompClient && stompClient.connected) {
            stompClient.send(`/app/webrtc/${type}`, {}, JSON.stringify(data));
        }
    },

    refreshDevices: () => {
        if (stompClient && stompClient.connected && registerPayload) {
            stompClient.send('/app/register', {}, registerPayload);
        }
    },

    createRoom: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/rooms/create', {}, '');
        }
    },

    joinRoom: (code) => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/rooms/join', {}, code);
        }
    },

    leaveRoom: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/rooms/leave', {}, '');
        }
    },

    hideFromNetwork: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/visibility/network/hide', {}, '');
        }
    },

    showOnNetwork: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/visibility/network/show', {}, '');
        }
    },

    hideFromRoom: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/visibility/room/hide', {}, '');
        }
    },

    showInRoom: () => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/visibility/room/show', {}, '');
        }
    }
};
