let stompClient = null;
let activeSocket = null;
let reconnectTimer = null;

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

            stompClient.send('/app/register', {}, deviceId);
        };

        const onError = () => {
            UI.updateConnectionStatus(false);
            reconnectTimer = setTimeout(
                () => SocketService.connect(deviceId, callbacks),
                RECONNECT_DELAY_MS
            );
        };

        stompClient.connect({}, onConnected, onError);
    },

    sendSignal: (type, data) => {
        if (stompClient && stompClient.connected) {
            stompClient.send(`/app/webrtc/${type}`, {}, JSON.stringify(data));
        }
    },

    refreshDevices: (deviceId) => {
        if (stompClient && stompClient.connected) {
            stompClient.send('/app/register', {}, deviceId);
        }
    }
};
