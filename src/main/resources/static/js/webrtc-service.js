const connections = new Map(); // peerId -> { pc, dc, remoteDescSet, pendingCandidates }
let onConnectionChange = null;

const WebRTCService = {
    init: (onChange) => { onConnectionChange = onChange; },
    isTransferring: (peerId) => connections.has(peerId),

    computeHash: async (file) => {
        const buffer = file instanceof Blob ? await file.arrayBuffer() : file;
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        return Array.from(new Uint8Array(hashBuffer))
            .map(b => b.toString(16).padStart(2, '0'))
            .join('');
    },

    createOffer: async (targetId, senderId, file) => {
        if (connections.has(targetId)) {
            UI.showAlert(`Already transferring to ${targetId}.`, 'info');
            return;
        }

        // Reserve slot immediately to block double-clicks before async work begins
        connections.set(targetId, { pc: null, dc: null, remoteDescSet: false, pendingCandidates: [] });
        onConnectionChange?.();

        UI.updateProgress(targetId, 0, 'Computing hash...');
        const fileHash = await WebRTCService.computeHash(file);

        const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
        const dc = pc.createDataChannel('fileTransfer', { ordered: true });
        Object.assign(connections.get(targetId), { pc, dc });

        dc.onopen = () => {
            UI.updateProgress(targetId, 0, 'Starting transfer...');
            WebRTCService.sendChunks(dc, file, targetId);
        };
        dc.onmessage = (msg) => {
            const ack = JSON.parse(msg.data);
            if (ack.type === 'ack') {
                const percent = Math.round((ack.receivedSize / file.size) * 100);
                UI.updateProgress(targetId, percent, `Sending: ${UI.formatFileSize(ack.receivedSize)}`);
                if (ack.receivedSize >= file.size) {
                    UI.showAlert('Sent successfully!', 'success');
                    WebRTCService.closeConnection(targetId);
                }
            }
        };

        pc.onicecandidate = (e) => {
            if (e.candidate) SocketService.sendSignal('ice-candidate', {
                candidate: e.candidate, targetDeviceId: targetId, senderDeviceId: senderId
            });
        };

        pc.onconnectionstatechange = () => {
            if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
                UI.showAlert('Connection lost.', 'error');
                WebRTCService.closeConnection(targetId);
            }
        };

        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        SocketService.sendSignal('offer', {
            offer, targetDeviceId: targetId, senderDeviceId: senderId,
            fileName: file.name, fileSize: file.size, fileHash
        });
    },

    sendChunks: (dc, file, targetId) => {
        const chunkSize = 16384;

        const readSlice = (offset) => {
            const slice = file.slice(offset, offset + chunkSize);
            const reader = new FileReader();
            reader.onload = (e) => {
                if (dc.readyState !== 'open') return;
                dc.send(e.target.result);
                const next = offset + e.target.result.byteLength;
                if (next < file.size) {
                    if (dc.bufferedAmount > chunkSize * 20) {
                        setTimeout(() => readSlice(next), 1);
                    } else {
                        readSlice(next);
                    }
                }
            };
            reader.readAsArrayBuffer(slice);
        };
        readSlice(0);
    },

    handleSignal: async (data, deviceId) => {
        if (data.offer) {
            UI.showConfirm(
                `${data.senderDeviceId} wants to send "${data.fileName}" (${UI.formatFileSize(data.fileSize)}). Accept?`,
                () => WebRTCService.handleOffer(data, deviceId)
            );
        } else if (data.answer) {
            const conn = connections.get(data.senderDeviceId);
            if (!conn?.pc) return;
            await conn.pc.setRemoteDescription(new RTCSessionDescription(data.answer));
            conn.remoteDescSet = true;
            for (const candidate of conn.pendingCandidates) {
                await conn.pc.addIceCandidate(new RTCIceCandidate(candidate));
            }
            conn.pendingCandidates = [];
        } else if (data.candidate) {
            const conn = connections.get(data.senderDeviceId);
            if (!conn?.pc) return;
            if (conn.remoteDescSet) {
                await conn.pc.addIceCandidate(new RTCIceCandidate(data.candidate));
            } else {
                conn.pendingCandidates.push(data.candidate);
            }
        }
    },

    handleOffer: async (data, deviceId) => {
        const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
        connections.set(data.senderDeviceId, { pc, dc: null, remoteDescSet: false, pendingCandidates: [] });

        pc.ondatachannel = (e) => {
            const receivedChunks = [];
            let receivedSize = 0;

            e.channel.onmessage = async (msg) => {
                receivedChunks.push(msg.data);
                receivedSize += msg.data.byteLength;
                e.channel.send(JSON.stringify({ type: 'ack', receivedSize }));
                UI.updateProgress(data.senderDeviceId, Math.round((receivedSize / data.fileSize) * 100), 'Receiving...');

                if (receivedSize >= data.fileSize) {
                    const blob = new Blob(receivedChunks);
                    UI.updateProgress(data.senderDeviceId, 100, 'Verifying integrity...');

                    const receivedHash = await WebRTCService.computeHash(blob);
                    if (receivedHash !== data.fileHash) {
                        UI.showAlert('File corrupted! Hash mismatch — do not use this file.', 'error');
                        WebRTCService.closeConnection(data.senderDeviceId);
                        return;
                    }

                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url; a.download = data.fileName; a.click();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                    UI.showAlert('File received and verified! ✓', 'success');
                    WebRTCService.closeConnection(data.senderDeviceId);
                }
            };
        };

        pc.onicecandidate = (e) => {
            if (e.candidate) SocketService.sendSignal('ice-candidate', {
                candidate: e.candidate, targetDeviceId: data.senderDeviceId, senderDeviceId: deviceId
            });
        };

        pc.onconnectionstatechange = () => {
            if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
                UI.showAlert('Connection lost.', 'error');
                WebRTCService.closeConnection(data.senderDeviceId);
            }
        };

        await pc.setRemoteDescription(new RTCSessionDescription(data.offer));
        const conn = connections.get(data.senderDeviceId);
        conn.remoteDescSet = true;
        for (const candidate of conn.pendingCandidates) {
            await pc.addIceCandidate(new RTCIceCandidate(candidate));
        }
        conn.pendingCandidates = [];

        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        SocketService.sendSignal('answer', {
            answer, targetDeviceId: data.senderDeviceId, senderDeviceId: deviceId
        });
    },

    closeConnection: (peerId) => {
        const conn = connections.get(peerId);
        if (conn) {
            conn.dc?.close();
            conn.pc?.close();
            connections.delete(peerId);
            onConnectionChange?.();
        }
    }
};
