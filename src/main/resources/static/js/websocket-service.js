let stompClient = null;

const SocketService = {
    connect: (deviceId, callbacks) => {
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        stompClient.debug = null;

        stompClient.connect({}, () => {
            UI.updateConnectionStatus(true);

            // Listen to the device list
            stompClient.subscribe('/topic/devices/' + deviceId, (msg) => {
                callbacks.onDevicesUpdate(JSON.parse(msg.body));
            });

            // Listen for WebRTC signals
            stompClient.subscribe('/topic/webrtc/' + deviceId, (msg) => {
                callbacks.onSignal(JSON.parse(msg.body));
            });

            stompClient.send('/app/register', {}, deviceId);
        }, (error) => {
            UI.updateConnectionStatus(false);
            setTimeout(() => SocketService.connect(deviceId, callbacks), 3000);
        });
    },

    sendSignal: (type, data) => {
        if (stompClient && stompClient.connected) {
            stompClient.send(`/app/webrtc/${type}`, {}, JSON.stringify(data));
        }
    },

};