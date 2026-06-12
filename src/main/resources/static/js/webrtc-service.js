// Per-peer connection state. Keyed by REMOTE device id.
// This allows multiple concurrent transfers without state collisions.
const connections = new Map();

const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];
const CHUNK_SIZE = 16384;
const BUFFER_HIGH_WATERMARK = CHUNK_SIZE * 64;
const BUFFER_LOW_WATERMARK = CHUNK_SIZE * 16;
const HASH_TIMEOUT_MS = 15000;          // give up hashing after 15s (iCloud fetch / mobile RAM)
const HANDSHAKE_TIMEOUT_MS = 120000;    // sender stops waiting for receiver after 2 min (mobile users take longer)
const DIALOG_TIMEOUT_MS = 90000;        // auto-decline incoming dialog after 90s of inactivity
const PROGRESS_ACK_INTERVAL_MS = 250;   // receiver -> sender progress sync, throttled to avoid flooding the channel
const COMPLETION_ACK_TIMEOUT_MS = 10000; // sender waits this long for a final progress-ack before reporting failure

const newConnectionState = () => ({
    peerConnection: null,
    dataChannel: null,
    pendingCandidates: [],

    // Sender side
    files: null,
    fileMetas: null,
    fileIndex: 0,
    fileOffset: 0,
    handshakeTimer: null,
    completionTimer: null,

    // Receiver side
    batchFiles: null,
    currentFile: null,
    currentFileIndex: -1,
    currentChunks: [],
    currentReceived: 0,

    // Batch-wide totals so the progress bar shows monotonic overall progress
    // (per-file percent reset to 0% between files made the bar jump backwards).
    totalBytes: 0,
    totalDoneBytes: 0,

    // Receiver -> sender progress sync (throttled "progress-ack" messages)
    lastAckSent: 0,

    transferStartTime: null,
    aborted: false
});

/**
 * Detach error/state handlers from a connection so that the normal teardown
 * (peer.close() on one side, propagating to the other) does NOT trigger false
 * "connection failed" alerts after a successful transfer. Browsers sometimes
 * transition ICE state through 'failed' briefly during clean shutdown.
 */
const silenceConnectionEvents = (conn) => {
    if (!conn) return;
    if (conn.peerConnection) {
        conn.peerConnection.oniceconnectionstatechange = null;
        conn.peerConnection.onconnectionstatechange = null;
        conn.peerConnection.onicecandidate = null;
    }
    if (conn.dataChannel) {
        conn.dataChannel.onerror = null;
    }
};

const closeConnection = (remoteDeviceId) => {
    const conn = connections.get(remoteDeviceId);
    if (!conn) return;
    conn.aborted = true;
    silenceConnectionEvents(conn);
    if (conn.handshakeTimer) {
        clearTimeout(conn.handshakeTimer);
        conn.handshakeTimer = null;
    }
    if (conn.completionTimer) {
        clearTimeout(conn.completionTimer);
        conn.completionTimer = null;
    }
    try { if (conn.dataChannel) conn.dataChannel.close(); } catch (e) { /* ignore */ }
    try { if (conn.peerConnection) conn.peerConnection.close(); } catch (e) { /* ignore */ }
    conn.currentChunks = [];
    connections.delete(remoteDeviceId);

    // Hide the progress section if no transfer is active anymore
    if (connections.size === 0 && typeof UI !== 'undefined' && UI.hideProgress) {
        setTimeout(() => {
            if (connections.size === 0) UI.hideProgress();
        }, 3000);
    }
};

const wireIceCandidate = (conn, remoteId, selfId, onIceStable) => {
    conn.peerConnection.onicecandidate = (e) => {
        // Some browsers signal end-of-candidates with a non-null event.candidate
        // whose `candidate` string is empty. Relaying that produces
        // `new RTCIceCandidate({candidate: "", ...})` on the other side, which
        // throws "Expected candidate: got " and aborts handleOffer entirely.
        if (e.candidate && e.candidate.candidate) {
            SocketService.sendSignal('ice-candidate', {
                candidate: e.candidate,
                targetDeviceId: remoteId,
                senderDeviceId: selfId
            });
        }
    };
    conn.peerConnection.oniceconnectionstatechange = () => {
        const state = conn.peerConnection.iceConnectionState;
        console.log('[WebRTC] ICE state for ' + remoteId + ':', state);
        if (state === 'failed') {
            UI.showAlert(
                'Peer connection failed (' + NameGenerator.getDisplayName(remoteId)
                + '). Check that both devices are on the same network.',
                'error'
            );
            closeConnection(remoteId);
        } else if ((state === 'connected' || state === 'completed') && typeof onIceStable === 'function') {
            onIceStable();
        }
    };
    conn.peerConnection.onconnectionstatechange = () => {
        const state = conn.peerConnection.connectionState;
        console.log('[WebRTC] Connection state for ' + remoteId + ':', state);
        if (state === 'failed') {
            UI.showAlert('Peer connection failed: ' + NameGenerator.getDisplayName(remoteId), 'error');
            closeConnection(remoteId);
        }
    };
};

const computeFileHash = async (file) => {
    if (!self.crypto || !self.crypto.subtle) return null;
    const buf = await file.arrayBuffer();
    const digest = await self.crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(digest))
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
};

const computeHashWithTimeout = (file) => {
    return Promise.race([
        computeFileHash(file).catch((err) => {
            console.warn('Hash failed for ' + file.name + ':', err);
            return null;
        }),
        new Promise((resolve) => setTimeout(() => {
            console.warn('Hash timeout for ' + file.name + ', sending without integrity tag');
            resolve(null);
        }, HASH_TIMEOUT_MS))
    ]);
};

const verifyBlobHash = async (blob, expected) => {
    try {
        if (!expected || !self.crypto || !self.crypto.subtle) return true;
        const buf = await blob.arrayBuffer();
        const digest = await self.crypto.subtle.digest('SHA-256', buf);
        const got = Array.from(new Uint8Array(digest))
            .map(b => b.toString(16).padStart(2, '0'))
            .join('');
        return got === expected;
    } catch (err) {
        console.warn('Integrity check skipped:', err);
        return true;
    }
};

const showAcceptDialog = (senderDeviceId, files, totalSize) => {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:9999;display:flex;align-items:center;justify-content:center;';

        const dialog = document.createElement('div');
        dialog.style.cssText = 'background:white;padding:30px;border-radius:15px;max-width:460px;width:90%;text-align:center;box-shadow:0 10px 30px rgba(0,0,0,0.3);';

        const title = document.createElement('h3');
        title.style.cssText = 'margin-bottom:15px;color:#667eea;';
        title.textContent = 'Incoming File Transfer';

        const message = document.createElement('p');
        message.style.cssText = 'margin-bottom:10px;color:#333;';
        const senderSpan = document.createElement('strong');
        senderSpan.textContent = NameGenerator.getDisplayName(senderDeviceId);
        message.appendChild(senderSpan);
        message.appendChild(document.createTextNode(' wants to send you ' + files.length + ' file' + (files.length > 1 ? 's' : '') + ':'));

        const fileList = document.createElement('div');
        fileList.style.cssText = 'margin:10px 0 20px;color:#555;font-size:0.9em;max-height:180px;overflow-y:auto;text-align:left;background:#f8f9fa;padding:10px 15px;border-radius:8px;';
        files.slice(0, 10).forEach(f => {
            const row = document.createElement('div');
            row.style.cssText = 'padding:3px 0;';
            const nm = document.createElement('strong');
            nm.textContent = f.name || '(unnamed)';
            row.appendChild(nm);
            row.appendChild(document.createTextNode(' — ' + UI.formatFileSize(f.size || 0)));
            fileList.appendChild(row);
        });
        if (files.length > 10) {
            const more = document.createElement('div');
            more.style.cssText = 'padding:3px 0;color:#888;';
            more.textContent = '… and ' + (files.length - 10) + ' more';
            fileList.appendChild(more);
        }
        const totalRow = document.createElement('div');
        totalRow.style.cssText = 'margin-top:6px;padding-top:6px;border-top:1px solid #dee2e6;font-weight:bold;';
        totalRow.textContent = 'Total: ' + UI.formatFileSize(totalSize);
        fileList.appendChild(totalRow);

        const btnContainer = document.createElement('div');
        btnContainer.style.cssText = 'display:flex;gap:10px;justify-content:center;';

        const acceptBtn = document.createElement('button');
        acceptBtn.textContent = '✓ Accept';
        acceptBtn.style.cssText = 'padding:14px 28px;background:linear-gradient(135deg,#667eea,#764ba2);color:white;border:none;border-radius:8px;cursor:pointer;font-size:1em;font-weight:bold;min-height:48px;min-width:120px;touch-action:manipulation;';

        const declineBtn = document.createElement('button');
        declineBtn.textContent = '✗ Decline';
        declineBtn.style.cssText = 'padding:14px 28px;background:#e9ecef;color:#333;border:none;border-radius:8px;cursor:pointer;font-size:1em;min-height:48px;min-width:120px;touch-action:manipulation;';

        let timeoutHandle = null;
        const cleanup = (result) => {
            if (timeoutHandle) clearTimeout(timeoutHandle);
            if (overlay.parentNode) document.body.removeChild(overlay);
            resolve(result);
        };

        acceptBtn.onclick = () => cleanup(true);
        declineBtn.onclick = () => cleanup(false);

        btnContainer.appendChild(acceptBtn);
        btnContainer.appendChild(declineBtn);
        dialog.appendChild(title);
        dialog.appendChild(message);
        dialog.appendChild(fileList);
        dialog.appendChild(btnContainer);
        overlay.appendChild(dialog);
        document.body.appendChild(overlay);

        // Auto-decline if the user doesn't decide in time, so the sender isn't stuck.
        timeoutHandle = setTimeout(() => cleanup(false), DIALOG_TIMEOUT_MS);
    });
};

// ----- Sender: stream one file then advance to the next ------------------
const sendBatch = (conn, remoteId) => {
    conn.fileIndex = 0;
    conn.transferStartTime = Date.now();
    conn.dataChannel.bufferedAmountLowThreshold = BUFFER_LOW_WATERMARK;
    conn.totalBytes = conn.files.reduce((s, f) => s + f.size, 0);
    conn.totalDoneBytes = 0;
    sendNextFile(conn, remoteId);
};

const sendNextFile = (conn, remoteId) => {
    if (conn.aborted) return;
    if (conn.fileIndex >= conn.files.length) {
        try { conn.dataChannel.send(JSON.stringify({ type: 'done' })); } catch (e) { /* ignore */ }
        UI.updateProgress(100, 'Finishing transfer to ' + NameGenerator.getDisplayName(remoteId) + '…');

        // dataChannel.send() only guarantees the data was handed to the local
        // buffer, not that the peer actually received it. Wait for the receiver's
        // final 'progress-ack' (totalDoneBytes >= totalBytes) before declaring
        // success — otherwise a connection that drops right at the end can show
        // "success" here while the other side never got the file.
        conn.completionTimer = setTimeout(() => {
            conn.completionTimer = null;
            silenceConnectionEvents(conn);
            UI.showAlert(
                'Transfer to ' + NameGenerator.getDisplayName(remoteId) + ' could not be confirmed — it may have failed.',
                'error'
            );
            closeConnection(remoteId);
        }, COMPLETION_ACK_TIMEOUT_MS);
        return;
    }

    const file = conn.files[conn.fileIndex];
    const meta = conn.fileMetas[conn.fileIndex];

    // File header (control message)
    try {
        conn.dataChannel.send(JSON.stringify({
            type: 'file',
            index: conn.fileIndex,
            name: file.name,
            size: file.size,
            hash: meta && meta.hash,
            total: conn.files.length
        }));
    } catch (err) {
        UI.showAlert('Send error: ' + err.message, 'error');
        closeConnection(remoteId);
        return;
    }

    streamFile(conn, remoteId, file, () => {
        conn.fileIndex++;
        sendNextFile(conn, remoteId);
    });
};

const streamFile = (conn, remoteId, file, onDone) => {
    let offset = 0;

    const sendNext = () => {
        if (conn.aborted) return;
        if (!conn.dataChannel || conn.dataChannel.readyState !== 'open') return;

        const slice = file.slice(offset, offset + CHUNK_SIZE);
        const reader = new FileReader();
        reader.onload = (e) => {
            if (conn.aborted) return;
            if (!conn.dataChannel || conn.dataChannel.readyState !== 'open') return;

            try {
                conn.dataChannel.send(e.target.result);
            } catch (err) {
                UI.showAlert('Send error: ' + err.message, 'error');
                closeConnection(remoteId);
                return;
            }
            const bytes = e.target.result.byteLength;
            offset += bytes;

            // Progress UI is driven by 'progress-ack' messages from the receiver
            // (see handleIncomingMessage / dataChannel.onmessage below) so the bar
            // reflects bytes actually delivered, not just bytes handed to the
            // local buffer.

            if (offset < file.size) {
                if (conn.dataChannel.bufferedAmount > BUFFER_HIGH_WATERMARK) {
                    conn.dataChannel.onbufferedamountlow = () => {
                        conn.dataChannel.onbufferedamountlow = null;
                        sendNext();
                    };
                } else {
                    sendNext();
                }
            } else {
                onDone();
            }
        };
        reader.onerror = () => {
            UI.showAlert('Failed to read ' + file.name + ': ' + (reader.error && reader.error.message), 'error');
            closeConnection(remoteId);
        };
        reader.readAsArrayBuffer(slice);
    };
    sendNext();
};

// ----- Receiver: batch-aware message handler -----------------------------
const handleIncomingMessage = async (conn, remoteId, data) => {
    if (conn.aborted) return;

    // Control messages arrive as text
    if (typeof data === 'string') {
        let ctrl;
        try { ctrl = JSON.parse(data); } catch (e) { return; }

        if (ctrl.type === 'file') {
            conn.currentFile = ctrl;
            conn.currentFileIndex = ctrl.index;
            conn.currentChunks = [];
            conn.currentReceived = 0;
            // NOTE: do NOT reset transferStartTime here — we use it for batch-wide
            // speed/progress so the bar/speed stay monotonic across files.
            if (!conn.transferStartTime) conn.transferStartTime = Date.now();
        } else if (ctrl.type === 'done') {
            // Detach failure handlers before teardown so we don't show a red
            // alert while the peer closes its side of the connection.
            silenceConnectionEvents(conn);
            if (conn.batchFiles && conn.batchFiles.length > 1) {
                UI.showAlert('All ' + conn.batchFiles.length + ' files received from ' + NameGenerator.getDisplayName(remoteId), 'success');
            }
            UI.updateTransferSpeed(0);
            closeConnection(remoteId);
        }
        return;
    }

    // Binary chunk
    if (!conn.currentFile) return;

    conn.currentChunks.push(data);
    conn.currentReceived += data.byteLength;
    conn.totalDoneBytes += data.byteLength;

    const expected = conn.currentFile.size;
    const elapsed = (Date.now() - conn.transferStartTime) / 1000;
    const speed = elapsed > 0 ? conn.totalDoneBytes / elapsed : 0;
    // Bar = monotonic overall batch progress; text = current file detail.
    const overallPercent = conn.totalBytes > 0
        ? Math.round((conn.totalDoneBytes / conn.totalBytes) * 100)
        : Math.round((conn.currentReceived / expected) * 100);
    const total = conn.batchFiles ? conn.batchFiles.length : 1;
    const idx = conn.currentFileIndex + 1;
    const label = total > 1
        ? `(${idx}/${total}) ${conn.currentFile.name}`
        : conn.currentFile.name;

    UI.updateProgress(
        overallPercent,
        `Receiving ${label}: ${UI.formatFileSize(conn.currentReceived)} / ${UI.formatFileSize(expected)}`
    );
    UI.updateTransferSpeed(speed);

    // Let the sender's progress bar track real delivery instead of how far ahead
    // its local send buffer has gotten. Throttled so large files don't flood the
    // channel with one ack per 16KB chunk.
    const now = Date.now();
    const isLastChunk = conn.currentReceived >= expected;
    if (conn.dataChannel && conn.dataChannel.readyState === 'open'
        && (isLastChunk || now - conn.lastAckSent >= PROGRESS_ACK_INTERVAL_MS)) {
        conn.lastAckSent = now;
        try {
            conn.dataChannel.send(JSON.stringify({ type: 'progress-ack', totalDoneBytes: conn.totalDoneBytes }));
        } catch (e) { /* ignore */ }
    }

    if (conn.currentReceived >= expected) {
        const fileMeta = conn.currentFile;
        const chunks = conn.currentChunks;
        conn.currentChunks = [];
        conn.currentFile = null;
        conn.currentReceived = 0;

        await finalizeReceivedFile(remoteId, fileMeta, chunks, total === 1);
    }
};

const finalizeReceivedFile = async (remoteId, fileMeta, chunks, isOnlyFile) => {
    const blob = new Blob(chunks);

    if (fileMeta.hash) {
        UI.updateProgress(100, 'Verifying ' + fileMeta.name + '…');
        const ok = await verifyBlobHash(blob, fileMeta.hash);
        if (!ok) {
            UI.showAlert('Integrity check FAILED for ' + fileMeta.name + ' — file dropped.', 'error');
            const conn = connections.get(remoteId);
            if (conn && conn.dataChannel && conn.dataChannel.readyState === 'open') {
                try {
                    conn.dataChannel.send(JSON.stringify({ type: 'integrity-error', name: fileMeta.name }));
                } catch (e) { /* ignore */ }
            }
            return;
        }
    }

    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileMeta.name || 'received_file';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1500);

    if (isOnlyFile) {
        UI.showAlert('File received from ' + NameGenerator.getDisplayName(remoteId), 'success');
    }
};

const WebRTCService = {
    createOffer: async (targetId, senderId, files) => {
        if (!Array.isArray(files)) files = [files];
        if (files.length === 0) return;
        if (connections.has(targetId)) {
            UI.showAlert('A transfer to ' + NameGenerator.getDisplayName(targetId) + ' is already in progress.', 'info');
            return;
        }

        const conn = newConnectionState();
        conn.files = files;
        connections.set(targetId, conn);

        try {
            conn.peerConnection = new RTCPeerConnection({ iceServers: ICE_SERVERS });

            // dataChannel.onopen can fire before the ICE connection has settled on its
            // final candidate pair. Starting the transfer right then risks a drop near
            // the end (the connection re-stabilizes mid-stream). Wait for BOTH the
            // channel to be open AND the ICE connection to report 'connected'/'completed'
            // before streaming any data.
            let dataChannelOpen = false;
            let iceStable = false;
            const tryStartTransfer = () => {
                if (!dataChannelOpen || !iceStable) return;
                if (conn.handshakeTimer) {
                    clearTimeout(conn.handshakeTimer);
                    conn.handshakeTimer = null;
                }
                sendBatch(conn, targetId);
            };

            wireIceCandidate(conn, targetId, senderId, () => {
                iceStable = true;
                tryStartTransfer();
            });

            conn.dataChannel = conn.peerConnection.createDataChannel('fileTransfer', { ordered: true });
            conn.dataChannel.binaryType = 'arraybuffer';
            conn.dataChannel.onopen = () => {
                dataChannelOpen = true;
                tryStartTransfer();
            };
            conn.dataChannel.onerror = (err) => {
                UI.showAlert('Send channel error: ' + (err.message || err.type || 'unknown'), 'error');
                closeConnection(targetId);
            };
            conn.dataChannel.onmessage = (e) => {
                let msg;
                try { msg = JSON.parse(e.data); } catch (err) { return; }
                if (msg.type === 'integrity-error') {
                    UI.showAlert(
                        NameGenerator.getDisplayName(targetId) + ' reported a corrupted transfer for ' + msg.name + '.',
                        'error'
                    );
                } else if (msg.type === 'progress-ack') {
                    const doneBytes = msg.totalDoneBytes || 0;
                    const elapsed = (Date.now() - conn.transferStartTime) / 1000;
                    const speed = elapsed > 0 ? doneBytes / elapsed : 0;

                    if (conn.completionTimer && conn.totalBytes > 0 && doneBytes >= conn.totalBytes) {
                        // Receiver confirmed it got every byte — now we can safely
                        // report success and tear down.
                        clearTimeout(conn.completionTimer);
                        conn.completionTimer = null;
                        silenceConnectionEvents(conn);
                        UI.updateProgress(100, 'All files sent to ' + NameGenerator.getDisplayName(targetId));
                        UI.updateTransferSpeed(0);
                        UI.showAlert('All files sent to ' + NameGenerator.getDisplayName(targetId), 'success');
                        setTimeout(() => closeConnection(targetId), 1000);
                        if (typeof onTransferComplete === 'function') onTransferComplete();
                        return;
                    }

                    const percent = conn.totalBytes > 0 ? Math.round((doneBytes / conn.totalBytes) * 100) : 0;
                    const file = conn.files[Math.min(conn.fileIndex, conn.files.length - 1)];
                    const label = conn.files.length > 1
                        ? `(${conn.fileIndex + 1}/${conn.files.length}) ${file.name}`
                        : file.name;

                    UI.updateProgress(
                        percent,
                        `Sending ${label}: ${UI.formatFileSize(doneBytes)} / ${UI.formatFileSize(conn.totalBytes)}`
                    );
                    UI.updateTransferSpeed(speed);
                }
            };

            // Hash each file (with timeout so iOS Photos lazy-load can't hang us)
            UI.updateProgress(0, `Preparing ${files.length} file${files.length > 1 ? 's' : ''}…`);
            conn.fileMetas = [];
            for (let i = 0; i < files.length; i++) {
                const f = files[i];
                UI.updateProgress(
                    Math.round((i / files.length) * 100),
                    `Hashing (${i + 1}/${files.length}): ${f.name}`
                );
                const hash = await computeHashWithTimeout(f);
                conn.fileMetas.push({ name: f.name, size: f.size, hash });
            }

            const offer = await conn.peerConnection.createOffer();
            await conn.peerConnection.setLocalDescription(offer);

            UI.updateProgress(0, 'Waiting for ' + NameGenerator.getDisplayName(targetId) + ' to accept…');

            // Give up if receiver never accepts/connects in time
            conn.handshakeTimer = setTimeout(() => {
                if (!conn.dataChannel || conn.dataChannel.readyState !== 'open') {
                    UI.showAlert('Receiver did not respond — transfer cancelled.', 'error');
                    closeConnection(targetId);
                }
            }, HANDSHAKE_TIMEOUT_MS);

            const totalSize = files.reduce((s, f) => s + f.size, 0);

            SocketService.sendSignal('offer', {
                offer,
                targetDeviceId: targetId,
                senderDeviceId: senderId,
                files: conn.fileMetas,
                totalSize,
                // backward compat
                fileName: files[0].name,
                fileSize: files[0].size
            });
        } catch (err) {
            UI.showAlert('Failed to start transfer: ' + err.message, 'error');
            closeConnection(targetId);
        }
    },

    handleSignal: async (data, deviceId) => {
        const remoteId = data.senderDeviceId;
        if (!remoteId) return;

        if (data.offer) {
            const files = Array.isArray(data.files) && data.files.length > 0
                ? data.files
                : [{ name: data.fileName, size: data.fileSize, hash: data.fileHash }];
            const totalSize = data.totalSize || files.reduce((s, f) => s + (f.size || 0), 0);

            // CRITICAL: pre-create a connection state BEFORE showing the dialog so that
            // ICE candidates the sender ships immediately after the offer (which always
            // arrive while the user is still reading the dialog) are buffered instead of
            // dropped. Without this, ICE negotiation cannot complete after acceptance.
            let conn = connections.get(remoteId);
            if (!conn) {
                conn = newConnectionState();
                connections.set(remoteId, conn);
            }

            const accepted = await showAcceptDialog(remoteId, files, totalSize);
            if (accepted) {
                await WebRTCService.handleOffer(data, deviceId, files);
            } else {
                SocketService.sendSignal('decline', {
                    targetDeviceId: remoteId,
                    senderDeviceId: deviceId,
                    decline: true
                });
                closeConnection(remoteId);
            }
            return;
        }

        if (data.decline) {
            const conn = connections.get(remoteId);
            if (conn) {
                UI.showAlert(NameGenerator.getDisplayName(remoteId) + ' declined the transfer.', 'info');
                UI.updateTransferSpeed(0);
                closeConnection(remoteId);
            }
            return;
        }

        const conn = connections.get(remoteId);
        if (!conn) return;

        if (data.answer) {
            if (!conn.peerConnection) return;
            try {
                await conn.peerConnection.setRemoteDescription(new RTCSessionDescription(data.answer));
                for (const candidate of conn.pendingCandidates) {
                    try {
                        await conn.peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
                    } catch (e) {
                        console.warn('Failed to flush queued ICE:', e);
                    }
                }
                conn.pendingCandidates = [];
                UI.updateProgress(0, 'Receiver accepted — establishing connection…');
            } catch (err) {
                UI.showAlert('Connection error: ' + err.message, 'error');
                closeConnection(remoteId);
            }
        } else if (data.candidate) {
            // Guard against null peerConnection (placeholder state while dialog is shown)
            if (conn.peerConnection && conn.peerConnection.remoteDescription) {
                try {
                    await conn.peerConnection.addIceCandidate(new RTCIceCandidate(data.candidate));
                } catch (err) {
                    console.warn('Failed to add ICE candidate:', err);
                }
            } else {
                conn.pendingCandidates.push(data.candidate);
            }
        }
    },

    handleOffer: async (data, deviceId, files) => {
        const remoteId = data.senderDeviceId;

        let conn = connections.get(remoteId);
        if (!conn) {
            conn = newConnectionState();
            connections.set(remoteId, conn);
        }
        conn.batchFiles = files;
        conn.totalBytes = files.reduce((s, f) => s + (f.size || 0), 0);
        conn.totalDoneBytes = 0;

        // If a stale peerConnection exists from a previous attempt, drop it cleanly
        // (keep pendingCandidates — they were buffered for THIS offer)
        if (conn.peerConnection) {
            try { conn.peerConnection.close(); } catch (e) { /* ignore */ }
            conn.peerConnection = null;
        }

        try {
            conn.peerConnection = new RTCPeerConnection({ iceServers: ICE_SERVERS });
            wireIceCandidate(conn, remoteId, deviceId);

            conn.peerConnection.ondatachannel = (e) => {
                conn.dataChannel = e.channel;
                conn.dataChannel.binaryType = 'arraybuffer';
                conn.transferStartTime = Date.now();

                e.channel.onmessage = (msg) => handleIncomingMessage(conn, remoteId, msg.data);
                e.channel.onerror = (err) => {
                    UI.showAlert('Receive channel error: ' + (err.message || err.type || 'unknown'), 'error');
                    closeConnection(remoteId);
                };
            };

            await conn.peerConnection.setRemoteDescription(new RTCSessionDescription(data.offer));
            for (const candidate of conn.pendingCandidates) {
                await conn.peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
            }
            conn.pendingCandidates = [];

            const answer = await conn.peerConnection.createAnswer();
            await conn.peerConnection.setLocalDescription(answer);

            SocketService.sendSignal('answer', {
                answer,
                targetDeviceId: remoteId,
                senderDeviceId: deviceId
            });
        } catch (err) {
            UI.showAlert('Failed to accept transfer: ' + err.message, 'error');
            closeConnection(remoteId);
        }
    },

    activeTransfers: () => connections.size,

    cancelAll: () => {
        for (const id of Array.from(connections.keys())) closeConnection(id);
    }
};

let onTransferComplete = null;
