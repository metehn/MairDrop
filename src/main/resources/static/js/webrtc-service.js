let peerConnection = null;
let dataChannel = null;
let transferStartTime = null;

const WebRTCService = {
    // The device that started the connection (Sender)
    createOffer: async (targetId, senderId, file) => {
        peerConnection = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
        });

        dataChannel = peerConnection.createDataChannel('fileTransfer', { ordered: true });
        WebRTCService.setupSenderChannel(file);

        peerConnection.onicecandidate = (e) => {
            if (e.candidate) SocketService.sendSignal('ice-candidate', {
                candidate: e.candidate, targetDeviceId: targetId, senderDeviceId: senderId
            });
        };

        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);

        SocketService.sendSignal('offer', {
            offer: offer, targetDeviceId: targetId, senderDeviceId: senderId,
            fileName: file.name, fileSize: file.size
        });
    },

    setupSenderChannel: (file) => {
        dataChannel.onopen = () => WebRTCService.sendChunks(file);
    },

    sendChunks: async (file) => {
        const chunkSize = 16384;
        let offset = 0;
        transferStartTime = Date.now();

        const readSlice = (o) => {
            const slice = file.slice(offset, o + chunkSize);
            const reader = new FileReader();
            reader.onload = (e) => {
                if (dataChannel.readyState === 'open') {
                    dataChannel.send(e.target.result);
                    offset += e.target.result.byteLength;

                    const percent = Math.round((offset / file.size) * 100);
                    UI.updateProgress(percent, `Sending: ${UI.formatFileSize(offset)}`);

                    if (offset < file.size) {
                        // Buffer check
                        if (dataChannel.bufferedAmount > chunkSize * 20) {
                            setTimeout(() => readSlice(offset), 1);
                        } else {
                            readSlice(offset);
                        }
                    } else {
                        UI.showAlert('Sent successfully!', 'success');
                    }
                }
            };
            reader.readAsArrayBuffer(slice);
        };
        readSlice(0);
    },

    handleSignal: async (data, deviceId) => {
        if (data.offer) {
            if (confirm(`${data.senderDeviceId} wants to send a file. Should it be accepted?`)) {
                WebRTCService.handleOffer(data, deviceId);
            }
        } else if (data.answer) {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(data.answer));
        } else if (data.candidate) {
            await peerConnection.addIceCandidate(new RTCIceCandidate(data.candidate));
        }
    },

    handleOffer: async (data, deviceId) => {
        peerConnection = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
        });

        peerConnection.ondatachannel = (e) => {
            const receivedChunks = [];
            let receivedSize = 0;
            transferStartTime = Date.now();

            e.channel.onmessage = (msg) => {
                receivedChunks.push(msg.data);
                receivedSize += msg.data.byteLength;
                UI.updateProgress(Math.round((receivedSize / data.fileSize) * 100), 'Receiving...');

                if (receivedSize >= data.fileSize) {
                    const blob = new Blob(receivedChunks);
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url; a.download = data.fileName; a.click();
                    UI.showAlert('Receive file!', 'success');
                }
            };
        };

        peerConnection.onicecandidate = (e) => {
            if (e.candidate) SocketService.sendSignal('ice-candidate', {
                candidate: e.candidate, targetDeviceId: data.senderDeviceId, senderDeviceId: deviceId
            });
        };

        await peerConnection.setRemoteDescription(new RTCSessionDescription(data.offer));
        const answer = await peerConnection.createAnswer();
        await peerConnection.setLocalDescription(answer);

        SocketService.sendSignal('answer', {
            answer: answer, targetDeviceId: data.senderDeviceId, senderDeviceId: deviceId
        });
    }
};