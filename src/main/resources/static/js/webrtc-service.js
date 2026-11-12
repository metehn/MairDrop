// Per-peer connection state. Keyed by REMOTE device id.
// This allows multiple concurrent transfers without state collisions.
const connections = new Map();

// Deferred "hide the progress bar" timers, keyed by remote device id. closeConnection hides the bar
// 3s after teardown; if a new transfer to the same device starts inside that window we must cancel
// the stale timer, otherwise it hides the new transfer's live progress bar.
const pendingHideTimers = new Map();
const cancelPendingHide = (remoteDeviceId) => {
    const t = pendingHideTimers.get(remoteDeviceId);
    if (t) {
        clearTimeout(t);
        pendingHideTimers.delete(remoteDeviceId);
    }
};

// Notify the UI layer that the set of active transfers changed, so per-device "Sending…"
// state re-renders without waiting for the next device-list broadcast. app.js assigns
// WebRTCService.onActiveTransfersChanged.
const notifyActiveTransfersChanged = () => {
    if (typeof WebRTCService !== 'undefined' && typeof WebRTCService.onActiveTransfersChanged === 'function') {
        WebRTCService.onActiveTransfersChanged();
    }
};

const notifyTransferComplete = () => {
    if (typeof WebRTCService !== 'undefined' && typeof WebRTCService.onTransferComplete === 'function') {
        WebRTCService.onTransferComplete();
    }
};

const ICE_SERVERS = (() => {
    try {
        const parsed = JSON.parse(ICE_SERVERS_JSON);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    } catch (e) { /* fall through to default */ }
    return [{ urls: 'stun:stun.l.google.com:19302' }];
})();
const CHUNK_SIZE = 16384;
const BUFFER_HIGH_WATERMARK = CHUNK_SIZE * 64;
const BUFFER_LOW_WATERMARK = CHUNK_SIZE * 16;
const HASH_TIMEOUT_MS = 15000;          // give up hashing after 15s (iCloud fetch / mobile RAM)
const HANDSHAKE_TIMEOUT_MS = 120000;    // sender stops waiting for receiver after 2 min (mobile users take longer)
const DIALOG_TIMEOUT_MS = 90000;        // auto-decline incoming dialog after 90s of inactivity
const PROGRESS_ACK_INTERVAL_MS = 250;   // receiver -> sender progress sync, throttled to avoid flooding the channel
const COMPLETION_ACK_TIMEOUT_MS = 10000; // sender waits this long for a final progress-ack before reporting failure
const ICE_DISCONNECTED_TIMEOUT_MS = 15000; // if ICE stays 'disconnected' this long, give up
const STALL_TIMEOUT_MS = 30000;         // if no bytes transferred in this window, close the connection

const newConnectionState = () => ({
    peerConnection: null,
    dataChannel: null,
    pendingCandidates: [],

    // Sender side
    files: null,
    fileMetas: null,
    fileIndex: 0,
    handshakeTimer: null,
    completionTimer: null,
    transferStarted: false,
    finalAckReceived: false,

    // Watchdog timers
    iceDisconnectedTimer: null,
    stallTimer: null,

    // Receiver side
    batchFiles: null,
    currentFile: null,
    currentFileIndex: -1,
    currentChunks: [],
    currentReceived: 0,
    pendingFinalize: 0,
    doneReceived: false,
    finalAckSent: false,
    expectedFiles: null,
    fileOutcomes: null,
    batchId: null,
    lastUiUpdate: 0,

    // Batch-wide totals so the progress bar shows monotonic overall progress
    // (per-file percent reset to 0% between files made the bar jump backwards).
    totalBytes: 0,
    totalDoneBytes: 0,

    // Receiver -> sender progress sync (throttled "progress-ack" messages)
    lastAckSent: 0,

    // Receiver: keep-alive interval sent while a per-file hash verify is in flight
    finalizeAliveTimer: null,
    // Receiver: files that passed verification, kept for the received-files panel
    receivedFiles: null,
    // Files whose integrity check failed, keyed by file index. The sender tracks failures
    // reported by the receiver via 'integrity-error'; the receiver tracks its own failed
    // verifications. Used for the final summary alert and per-file continuation.
    failedFiles: null,
    // Sender: last byte count acked by the receiver (drives the lazy-hash progress label)
    lastAckDoneBytes: 0,

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

const closeConnection = (remoteDeviceId, existingConn) => {
    const conn = existingConn || connections.get(remoteDeviceId);
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
    if (conn.iceDisconnectedTimer) {
        clearTimeout(conn.iceDisconnectedTimer);
        conn.iceDisconnectedTimer = null;
    }
    if (conn.stallTimer) {
        clearTimeout(conn.stallTimer);
        conn.stallTimer = null;
    }
    if (conn.finalizeAliveTimer) {
        clearInterval(conn.finalizeAliveTimer);
        conn.finalizeAliveTimer = null;
    }
    try { if (conn.dataChannel) conn.dataChannel.close(); } catch (e) { /* ignore */ }
    try { if (conn.peerConnection) conn.peerConnection.close(); } catch (e) { /* ignore */ }
    conn.currentChunks = [];

    // Only remove the map entry if it still belongs to THIS connection. A delayed teardown
    // (reportSendSuccess schedules closeConnection 1s later) must never delete a newer
    // transfer that started to the same device in that window, and must not hide that
    // newer transfer's live progress bar either.
    const removed = connections.get(remoteDeviceId) === conn;
    if (removed) {
        connections.delete(remoteDeviceId);
    }

    if (typeof UI !== 'undefined' && UI.hideProgress && !connections.has(remoteDeviceId)) {
        cancelPendingHide(remoteDeviceId);
        const timer = setTimeout(() => {
            pendingHideTimers.delete(remoteDeviceId);
            // Re-check on fire: a new transfer to this device may have started while this
            // timer was pending.
            if (!connections.has(remoteDeviceId)) {
                UI.hideProgress(remoteDeviceId);
            }
        }, 3000);
        pendingHideTimers.set(remoteDeviceId, timer);
    }

    // A verified file is never lost just because the connection later closed — surface any
    // completed files in the received-files panel even when the batch ended early.
    if (conn.receivedFiles && conn.receivedFiles.length > 0) {
        renderReceivedFilesPanel(true);
    }

    // Let the device list re-render so this peer's "Sending..." state clears — but only
    // when this connection actually owned the map slot (a newer connection's state is
    // none of this teardown's business).
    if (removed) {
        notifyActiveTransfersChanged();
    }
};

const computeSpeed = (doneBytes, startTime) => {
    if (!startTime) return 0;
    const elapsed = (Date.now() - startTime) / 1000;
    return elapsed > 0 ? doneBytes / elapsed : 0;
};

const formatFileLabel = (name, idx, total) => total > 1 ? `(${idx}/${total}) ${name}` : name;

// Overall batch percent. Falls back to per-file percent when totalBytes is
// unknown (0), and to 100% once a 0-byte file's only chunk-equivalent (the
// 'file'/'done' control messages) has been processed.
const computePercent = (doneBytes, totalBytes, fallbackDone, fallbackExpected) => {
    if (totalBytes > 0) return Math.round((doneBytes / totalBytes) * 100);
    if (fallbackExpected > 0) return Math.round((fallbackDone / fallbackExpected) * 100);
    return 100;
};

const trySendMessage = (dataChannel, payload) => {
    if (!dataChannel || dataChannel.readyState !== 'open') return false;
    try {
        dataChannel.send(JSON.stringify(payload));
        return true;
    } catch (e) {
        return false;
    }
};

const resetStallTimer = (conn, remoteId) => {
    if (conn.stallTimer) clearTimeout(conn.stallTimer);
    conn.stallTimer = null;
    // While a file verify is in flight no bytes arrive — the watchdog must not fire and
    // kill a healthy transfer. The finalize keep-alive interval protects the sender's
    // timers instead; we re-arm here once pendingFinalize drops back to 0.
    if (conn.pendingFinalize > 0) return;
    conn.stallTimer = setTimeout(() => {
        if (!conn.aborted) {
            UI.showAlert('Transfer stalled — no data for 30s. Connection closed.', 'error');
            closeConnection(remoteId);
        }
    }, STALL_TIMEOUT_MS);
};

const sendProgressAck = (conn, force) => {
    const now = Date.now();
    if (!force && now - conn.lastAckSent < PROGRESS_ACK_INTERVAL_MS) return;
    conn.lastAckSent = now;
    trySendMessage(conn.dataChannel, { type: 'progress-ack', totalDoneBytes: conn.totalDoneBytes });
};

// Every file in the batch must have a recorded verify outcome before the final ack can go
// out. pendingFinalize === 0 only means nothing is IN FLIGHT; a file whose chunks never
// fully arrived has no outcome, and acknowledging the batch then would claim success for a
// file that was never verified.
const allFilesFinalized = (conn) => {
    const total = conn.batchFiles ? conn.batchFiles.length : conn.expectedFiles;
    if (total == null) return true;
    if (!conn.fileOutcomes) return false;
    for (let i = 0; i < total; i++) {
        if (!(i in conn.fileOutcomes)) return false;
    }
    return true;
};

// Receiver-side teardown: only safe once the 'done' control message has
// arrived AND every in-flight finalizeReceivedFile() has settled AND every file has a
// recorded outcome, otherwise closeConnection() can run out from under a pending
// hash/verify/download.
const finishReceivingIfReady = (conn, remoteId) => {
    if (!conn.doneReceived || conn.pendingFinalize > 0) return;
    if (!allFilesFinalized(conn)) return;

    // Explicit final batch ack: sent exactly once, only after the WHOLE batch has been
    // verified. Ordinary byte-progress acks must never be treated as completion by the
    // sender — the first verification to finish can no longer mask a later integrity
    // failure. The ack carries the per-file outcome summary so the sender's final alert
    // reflects reality even if an 'integrity-error' control message got dropped on the wire.
    if (!conn.finalAckSent) {
        conn.finalAckSent = true;
        const total = conn.batchFiles ? conn.batchFiles.length : (conn.expectedFiles || 1);
        const failedIndexes = conn.failedFiles ? Object.keys(conn.failedFiles).map(Number) : [];
        trySendMessage(conn.dataChannel, {
            type: 'final-ack',
            totalFiles: total,
            failedIndexes: failedIndexes
        });
    }

    silenceConnectionEvents(conn);
    if (conn.batchFiles && conn.batchFiles.length > 1) {
        const failedCount = conn.failedFiles ? Object.keys(conn.failedFiles).length : 0;
        const displayName = NameGenerator.getDisplayName(remoteId);
        if (failedCount === 0) {
            UI.showAlert('All ' + conn.batchFiles.length + ' files received from ' + displayName, 'success');
        } else {
            UI.showAlert(
                (conn.batchFiles.length - failedCount) + ' of ' + conn.batchFiles.length
                + ' files received from ' + displayName + ', ' + failedCount + ' failed',
                'error'
            );
        }
    }
    UI.updateTransferSpeed(0, remoteId);
    closeConnection(remoteId);
};

const reportSendSuccess = (conn, remoteId) => {
    if (conn.completionTimer) {
        clearTimeout(conn.completionTimer);
        conn.completionTimer = null;
    }
    if (conn.stallTimer) {
        clearTimeout(conn.stallTimer);
        conn.stallTimer = null;
    }
    silenceConnectionEvents(conn);

    const displayName = NameGenerator.getDisplayName(remoteId);
    const failedCount = conn.failedFiles ? Object.keys(conn.failedFiles).length : 0;
    const total = conn.files ? conn.files.length : 0;
    const summary = failedCount === 0
        ? 'All files sent to ' + displayName
        : (total - failedCount) + ' of ' + total + ' files sent to ' + displayName + ', ' + failedCount + ' failed';

    UI.updateProgress(remoteId, 100, summary);
    UI.updateTransferSpeed(0, remoteId);
    UI.showAlert(summary, failedCount === 0 ? 'success' : 'error');

    // Free the connection slot immediately so a re-send to the same device can start right
    // away. The delayed closeConnection below only tears down the now-idle channel and hides
    // the progress bar after the usual grace period (it receives the conn explicitly because
    // the map entry is already gone).
    connections.delete(remoteId);
    notifyActiveTransfersChanged();

    // Keep the channel open for a moment so the trailing 'done' control message can still
    // reach the receiver before we tear down.
    setTimeout(() => closeConnection(remoteId, conn), 1000);

    notifyTransferComplete();
};

const reportSendFailure = (conn, remoteId, message) => {
    if (conn.completionTimer) {
        clearTimeout(conn.completionTimer);
        conn.completionTimer = null;
    }
    if (conn.stallTimer) {
        clearTimeout(conn.stallTimer);
        conn.stallTimer = null;
    }
    silenceConnectionEvents(conn);
    UI.showAlert(message, 'error');
    closeConnection(remoteId);
};

// Inspects the negotiated ICE candidate pair to tell whether data is flowing
// directly between peers or through a TURN relay, so the UI can show which.
const reportConnectionType = async (peerConnection, remoteId) => {
    try {
        const stats = await peerConnection.getStats();
        let activePair = null;
        stats.forEach((report) => {
            if (report.type === 'candidate-pair' && report.state === 'succeeded'
                    && (report.selected || report.nominated)) {
                activePair = report;
            }
        });
        if (!activePair) return;

        const local = stats.get(activePair.localCandidateId);
        const remote = stats.get(activePair.remoteCandidateId);
        const isRelay = (local && local.candidateType === 'relay')
                || (remote && remote.candidateType === 'relay');
        UI.setConnectionType(remoteId, isRelay);
    } catch (e) { /* stats unavailable — leave the default styling */ }
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

        // Clear any pending disconnected timer on every state change
        if (conn.iceDisconnectedTimer) {
            clearTimeout(conn.iceDisconnectedTimer);
            conn.iceDisconnectedTimer = null;
        }

        if (state === 'disconnected') {
            // Transient — network hiccup, mobile switching cell towers, etc.
            // Give it time to recover before giving up.
            conn.iceDisconnectedTimer = setTimeout(() => {
                if (conn.peerConnection &&
                        conn.peerConnection.iceConnectionState === 'disconnected') {
                    UI.showAlert('Connection dropped — transfer failed.', 'error');
                    closeConnection(remoteId);
                }
            }, ICE_DISCONNECTED_TIMEOUT_MS);
        } else if (state === 'failed') {
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
    resetStallTimer(conn, remoteId); // arm watchdog for the full send session
    sendNextFile(conn, remoteId);
};

const sendNextFile = async (conn, remoteId) => {
    if (conn.aborted) return;
    // The lazy hash below can suspend this send; if an integrity-error skip advances the index
    // meanwhile, resuming would pair an already-advanced index with the stale file/meta captured
    // above, so stop instead.
    const fileIndexAtEntry = conn.fileIndex;
    if (conn.fileIndex >= conn.files.length) {
        trySendMessage(conn.dataChannel, { type: 'done' });
        UI.updateProgress(remoteId, 100, 'Finishing transfer to ' + NameGenerator.getDisplayName(remoteId) + '…');

        // Detach state/error handlers now: a brief ICE 'failed' during normal
        // teardown is expected from this point on and must not surface as a
        // false "Peer connection failed" alert for an otherwise-successful send.
        silenceConnectionEvents(conn);

        // Completion is declared ONLY on the receiver's explicit 'final-ack', which it
        // sends after every file's hash verification has settled — never on ordinary
        // byte-progress acks (those arrive while verifications may still be running).
        if (conn.finalAckReceived) {
            reportSendSuccess(conn, remoteId);
            return;
        }

        // dataChannel.send() only guarantees the data was handed to the local
        // buffer, not that the peer actually received and verified it. Wait for the
        // receiver's 'final-ack' before declaring success — otherwise a connection
        // that drops right at the end can show "success" here while the other side
        // never got the file.
        conn.completionTimer = setTimeout(() => {
            conn.completionTimer = null;
            reportSendFailure(
                conn, remoteId,
                'Transfer to ' + NameGenerator.getDisplayName(remoteId) + ' could not be confirmed — it may have failed.'
            );
        }, COMPLETION_ACK_TIMEOUT_MS);
        return;
    }

    const file = conn.files[conn.fileIndex];
    const meta = conn.fileMetas[conn.fileIndex];

    // Lazy per-file hashing: the offer went out immediately (totalBytes was known from file
    // sizes) and each file is hashed only just before its header is sent, so a big batch
    // doesn't stall for minutes before the connection is even established.
    if (meta && meta.hash === undefined) {
        const idxLabel = conn.fileIndex + 1;
        const totalFiles = conn.files.length;
        const pct = conn.totalBytes > 0
            ? Math.round((conn.lastAckDoneBytes / conn.totalBytes) * 100)
            : Math.round((conn.fileIndex / Math.max(totalFiles, 1)) * 100);
        UI.updateProgress(remoteId, pct, `Hashing (${idxLabel}/${totalFiles}): ${file.name}`);
        meta.hash = await computeHashWithTimeout(file);
        if (conn.aborted || conn.fileIndex !== fileIndexAtEntry) return;
    }

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
    // If an integrity error causes this file to be skipped (fileIndex advanced past it),
    // stop streaming: chunks from a stale stream must not interleave with the next file.
    const myIndex = conn.fileIndex;

    const sendNext = () => {
        if (conn.aborted || conn.fileIndex !== myIndex) return;
        if (!conn.dataChannel || conn.dataChannel.readyState !== 'open') return;

        const slice = file.slice(offset, offset + CHUNK_SIZE);
        const reader = new FileReader();
        reader.onload = (e) => {
            if (conn.aborted || conn.fileIndex !== myIndex) return;
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
                if (conn.fileIndex === myIndex) onDone();
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

// ----- Receiver: received-files panel helpers -----------------------------
// Every verified file is registered here (name + size + object URL) so the user can save
// any of them with a real click (user gesture). Browsers block 2nd..Nth automatic
// downloads, so only the first file of a batch is auto-downloaded.
//
// ONE shared registry feeds the panel: concurrent batches from different devices merge
// into the same list instead of erasing each other. Entries are tagged with their
// (device, batch) origin, so a new batch replaces only its own predecessor's entries,
// and every replaced/removed entry's object URL is revoked.
let receivedFilesBatchSeq = 0;
const receivedFilesRegistry = [];

const renderReceivedFilesPanel = (forceShow) => {
    const panel = document.getElementById('receivedFilesPanel');
    if (!panel) return;
    const list = document.getElementById('receivedFilesList');
    if (!list) return;

    list.innerHTML = '';
    receivedFilesRegistry.forEach((entry) => {
        const item = document.createElement('li');
        item.className = 'received-file-item';

        const name = document.createElement('span');
        name.className = 'received-file-name';
        name.textContent = '📎 ' + (entry.name || 'received_file');
        name.title = entry.name || 'received_file';

        const size = document.createElement('span');
        size.className = 'received-file-size';
        size.textContent = UI.formatFileSize(entry.size || 0);

        const saveBtn = document.createElement('button');
        saveBtn.type = 'button';
        saveBtn.className = 'received-file-save';
        saveBtn.textContent = '💾 Save';
        saveBtn.dataset.url = entry.url;
        saveBtn.onclick = () => {
            const a = document.createElement('a');
            a.href = entry.url;
            a.download = entry.name || 'received_file';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        };

        item.appendChild(name);
        item.appendChild(size);
        item.appendChild(saveBtn);
        list.appendChild(item);
    });

    if (forceShow) panel.style.display = 'block';
    if (receivedFilesRegistry.length === 0) panel.style.display = 'none';
};

// A new batch from a device replaces that device's previous-batch entries (preserving
// every OTHER device's entries) and revokes the object URLs of the replaced ones.
const clearReceivedFilesForRemote = (remoteId, newBatchKey) => {
    for (let i = receivedFilesRegistry.length - 1; i >= 0; i--) {
        const entry = receivedFilesRegistry[i];
        if (entry.remoteId === remoteId && entry.batchKey !== newBatchKey) {
            try { URL.revokeObjectURL(entry.url); } catch (e) { /* ignore */ }
            receivedFilesRegistry.splice(i, 1);
        }
    }
    renderReceivedFilesPanel();
};

// ----- Receiver: per-file finalize with keep-alive ------------------------
// While a file's SHA-256 verify runs (can take >10s on large files), send the sender an
// 'alive' message every 3s so its completion/stall timers keep getting re-armed, and keep
// our own stall watchdog paused — otherwise a slow verify looks like a dead transfer.
const startFinalizeAlive = (conn) => {
    if (conn.stallTimer) {
        clearTimeout(conn.stallTimer);
        conn.stallTimer = null;
    }
    // Tell the sender we have all bytes and are now verifying — this resets its stall and
    // completion timers so a slow SHA-256 doesn't trigger a false failure.
    trySendMessage(conn.dataChannel, { type: 'alive' });
    if (conn.finalizeAliveTimer) return; // already running for an earlier file
    conn.finalizeAliveTimer = setInterval(() => {
        trySendMessage(conn.dataChannel, { type: 'alive' });
    }, 3000);
};

const stopFinalizeAlive = (conn) => {
    if (conn.finalizeAliveTimer) {
        clearInterval(conn.finalizeAliveTimer);
        conn.finalizeAliveTimer = null;
    }
};

const finalizeCurrentFile = (conn, remoteId, fileMeta, chunks, isOnlyFile) => {
    conn.pendingFinalize++;
    startFinalizeAlive(conn);
    finalizeReceivedFile(conn, remoteId, fileMeta, chunks, isOnlyFile)
        .finally(() => {
            conn.pendingFinalize--;
            // Ack the received byte count even after an integrity failure: the sender tracks
            // failures via 'integrity-error' messages, and a missing final ack would make it
            // declare a false "could not be confirmed" timeout.
            sendProgressAck(conn, true);
            if (conn.pendingFinalize === 0) {
                stopFinalizeAlive(conn);
                if (!conn.doneReceived && !conn.aborted) resetStallTimer(conn, remoteId);
            }
            finishReceivingIfReady(conn, remoteId);
        });
};

// ----- Receiver: batch-aware message handler -----------------------------
const handleIncomingMessage = (conn, remoteId, data) => {
    if (conn.aborted) return;

    // Control messages arrive as text
    if (typeof data === 'string') {
        let ctrl;
        try { ctrl = JSON.parse(data); } catch (e) { return; }

        if (ctrl.type === 'file') {
            resetStallTimer(conn, remoteId); // sender is alive between files — reset watchdog
            if (typeof ctrl.total === 'number') conn.expectedFiles = ctrl.total;
            // A 0-byte file never produces binary chunks; the arrival of the next control
            // message proves the sender finished it, so finalize it now.
            if (conn.currentFile && conn.currentFile.size === 0) {
                const total = conn.batchFiles ? conn.batchFiles.length : 1;
                finalizeCurrentFile(conn, remoteId, conn.currentFile, [], total === 1);
            }
            conn.currentFile = ctrl;
            conn.currentFileIndex = ctrl.index;
            conn.currentChunks = [];
            conn.currentReceived = 0;
            // NOTE: do NOT reset transferStartTime here — we use it for batch-wide
            // speed/progress so the bar/speed stay monotonic across files.
            if (!conn.transferStartTime) conn.transferStartTime = Date.now();
        } else if (ctrl.type === 'done') {
            // Same as above: the trailing empty file (if any) finalizes before teardown.
            if (conn.currentFile && conn.currentFile.size === 0) {
                const total = conn.batchFiles ? conn.batchFiles.length : 1;
                finalizeCurrentFile(conn, remoteId, conn.currentFile, [], total === 1);
            }
            // Teardown is deferred until any finalizeReceivedFile() calls still
            // in flight for the last file(s) have settled (see
            // finishReceivingIfReady) — closing the connection here could race
            // with a pending hash/verify/download for the final chunk.
            conn.doneReceived = true;
            finishReceivingIfReady(conn, remoteId);
        }
        return;
    }

    // Binary chunk
    if (!conn.currentFile) return;

    resetStallTimer(conn, remoteId); // sender is alive — reset watchdog
    conn.currentChunks.push(data);
    conn.currentReceived += data.byteLength;
    conn.totalDoneBytes += data.byteLength;

    const expected = conn.currentFile.size;
    const isLastChunk = conn.currentReceived >= expected;
    const total = conn.batchFiles ? conn.batchFiles.length : 1;

    // Throttle UI updates so large files don't repaint on every 16KB chunk.
    const now = Date.now();
    if (isLastChunk || now - conn.lastUiUpdate >= PROGRESS_ACK_INTERVAL_MS) {
        conn.lastUiUpdate = now;
        // Bar = monotonic overall batch progress; label = current file + batch percent.
        const overallPercent = computePercent(conn.totalDoneBytes, conn.totalBytes, conn.currentReceived, expected);
        const idx = conn.currentFileIndex + 1;
        const label = formatFileLabel(conn.currentFile.name, idx, total);

        UI.updateProgress(
            remoteId,
            overallPercent,
            `Receiving ${label} — ${overallPercent}%`
        );
        UI.updateTransferSpeed(computeSpeed(conn.totalDoneBytes, conn.transferStartTime), remoteId);
    }

    if (!isLastChunk) {
        // Let the sender's progress bar track real delivery instead of how far
        // ahead its local send buffer has gotten.
        sendProgressAck(conn, false);
        return;
    }

    const fileMeta = conn.currentFile;
    const chunks = conn.currentChunks;
    conn.currentChunks = [];
    conn.currentFile = null;
    conn.currentReceived = 0;
    finalizeCurrentFile(conn, remoteId, fileMeta, chunks, total === 1);
};

const finalizeReceivedFile = async (conn, remoteId, fileMeta, chunks, isOnlyFile) => {
    const blob = new Blob(chunks);
    // Capture the batch this file belongs to NOW: a new offer for the same device can
    // reset conn.batchId while this async verification is still running, and this file's
    // registry entry must stay tagged with its real batch.
    const batchKey = conn.batchId;

    if (fileMeta.hash) {
        UI.updateProgress(remoteId, 100, 'Verifying ' + fileMeta.name + '…');
        const ok = await verifyBlobHash(blob, fileMeta.hash);
        if (!ok) {
            UI.showAlert('Integrity check FAILED for ' + fileMeta.name + ' — file dropped.', 'error');
            if (conn) {
                if (!conn.fileOutcomes) conn.fileOutcomes = {};
                conn.fileOutcomes[fileMeta.index] = false;
                if (!conn.failedFiles) conn.failedFiles = {};
                conn.failedFiles[fileMeta.index] = true;
                trySendMessage(conn.dataChannel, { type: 'integrity-error', name: fileMeta.name, index: fileMeta.index });
                // If the sender's next header hasn't arrived yet, drop this file's state so the
                // next 'file' header (or the 'done' message) starts clean.
                if (conn.currentFile && conn.currentFileIndex === fileMeta.index) {
                    conn.currentFile = null;
                    conn.currentChunks = [];
                    conn.currentReceived = 0;
                }
            }
            return false;
        }
    }

    if (conn) {
        if (!conn.fileOutcomes) conn.fileOutcomes = {};
        conn.fileOutcomes[fileMeta.index] = true;
    }

    // A verified file is always kept, even if the connection teardown raced the verification
    // (e.g. the sender hit its completion timeout while we were hashing). Register it in the
    // shared received-files registry tagged with its (device, batch) origin; the object URL is
    // revoked when a newer batch from the same device replaces the entry.
    const url = URL.createObjectURL(blob);
    if (!conn.receivedFiles) conn.receivedFiles = [];
    conn.receivedFiles.push({ name: fileMeta.name || 'received_file', size: blob.size, url });
    receivedFilesRegistry.push({
        name: fileMeta.name || 'received_file',
        size: blob.size,
        url,
        remoteId,
        batchKey
    });
    // If the connection ended while this verification was in flight, make the file visible
    // right away — no batch-completion teardown is left that would show the panel later.
    renderReceivedFilesPanel(conn.aborted);

    // Browsers block 2nd..Nth automatic downloads without a user gesture, so only the first
    // file of the batch is auto-downloaded. Every file (including this one) stays in the
    // received-files panel with a Save button for a gesture-backed re-download.
    if (fileMeta.index === 0) {
        const a = document.createElement('a');
        a.href = url;
        a.download = fileMeta.name || 'received_file';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    if (isOnlyFile) {
        UI.showAlert('File received from ' + NameGenerator.getDisplayName(remoteId), 'success');
    }
    return true;
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
        // Per-file metadata is known up front (sizes are), but hashes are computed lazily
        // inside sendNextFile just before each file's header so a large batch doesn't block
        // for minutes before the offer is even sent. hash === undefined means "not yet".
        conn.fileMetas = files.map((f) => ({ name: f.name, size: f.size, hash: undefined }));
        connections.set(targetId, conn);
        cancelPendingHide(targetId); // don't let a just-finished transfer's hide timer hide this one
        notifyActiveTransfersChanged();

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
                conn.transferStarted = true;
                sendBatch(conn, targetId);
            };

            wireIceCandidate(conn, targetId, senderId, () => {
                iceStable = true;
                reportConnectionType(conn.peerConnection, targetId);
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
                if (conn.aborted) return;
                let msg;
                try { msg = JSON.parse(e.data); } catch (err) { return; }
                if (msg.type === 'integrity-error') {
                    // One corrupted file must not kill the rest of the batch. Record the
                    // failure and keep going — the final success/failure summary accounts
                    // for it.
                    const failedIndex = typeof msg.index === 'number' ? msg.index : conn.fileIndex;
                    if (!conn.failedFiles) conn.failedFiles = {};
                    conn.failedFiles[failedIndex] = msg.name || true;
                    UI.showAlert(
                        NameGenerator.getDisplayName(targetId) + ' reported a corrupted transfer for ' + msg.name + '.',
                        'error'
                    );
                    // Normally the sender has already moved past this file (the error only
                    // arrives once the receiver has all of its bytes). If not, skip it and
                    // move straight to the next one.
                    if (conn.fileIndex <= failedIndex) {
                        conn.fileIndex = failedIndex + 1;
                        sendNextFile(conn, targetId);
                    }
                } else if (msg.type === 'alive') {
                    // Receiver is verifying a large file hash — reset timers so we don't
                    // declare failure before verification completes. Each 'alive' re-arms
                    // the completion timer, so a slow SHA-256 never trips the 10s timeout.
                    resetStallTimer(conn, targetId);
                    if (conn.completionTimer) {
                        clearTimeout(conn.completionTimer);
                        conn.completionTimer = setTimeout(() => {
                            conn.completionTimer = null;
                            reportSendFailure(
                                conn, targetId,
                                'Transfer to ' + NameGenerator.getDisplayName(targetId) + ' could not be confirmed — it may have failed.'
                            );
                        }, COMPLETION_ACK_TIMEOUT_MS);
                    }
                } else if (msg.type === 'progress-ack') {
                    if (conn.finalAckReceived) return; // completion already decided
                    const doneBytes = msg.totalDoneBytes || 0;
                    resetStallTimer(conn, targetId); // receiver is alive — reset watchdog
                    conn.lastAckDoneBytes = doneBytes;

                    // Ordinary byte-progress acks NEVER complete a batch: having every byte
                    // does not mean every hash verification has settled. Completion comes
                    // only from the explicit 'final-ack' message below.
                    const percent = computePercent(doneBytes, conn.totalBytes, 0, 0);
                    const file = conn.files[Math.min(conn.fileIndex, conn.files.length - 1)];
                    const label = formatFileLabel(file.name, conn.fileIndex + 1, conn.files.length);

                    UI.updateProgress(
                        targetId,
                        percent,
                        `Sending ${label} — ${percent}%`
                    );
                    UI.updateTransferSpeed(computeSpeed(doneBytes, conn.transferStartTime), targetId);
                } else if (msg.type === 'final-ack') {
                    // Explicit completion from the receiver: sent only once every file's
                    // verification has settled. Merge the receiver's per-file outcome
                    // summary into our own failure record so the final alert reflects
                    // reality even if an 'integrity-error' control message was lost.
                    resetStallTimer(conn, targetId);
                    if (Array.isArray(msg.failedIndexes)) {
                        if (!conn.failedFiles) conn.failedFiles = {};
                        msg.failedIndexes.forEach((idx) => {
                            if (!conn.failedFiles[idx]) conn.failedFiles[idx] = true;
                        });
                    }
                    conn.finalAckReceived = true;
                    if (conn.completionTimer) {
                        reportSendSuccess(conn, targetId);
                    }
                    return;
                }
            };

            // Send the offer immediately — hashing happens lazily per file inside the batch
            // loop (see sendNextFile), so many large files no longer mean minutes of dead
            // time before the receiver is even asked.
            const offer = await conn.peerConnection.createOffer();
            await conn.peerConnection.setLocalDescription(offer);

            UI.updateProgress(targetId, 0, 'Waiting for ' + NameGenerator.getDisplayName(targetId) + ' to accept…');

            // Give up if receiver never accepts/connects (data channel open AND
            // ICE stable) in time.
            conn.handshakeTimer = setTimeout(() => {
                if (!conn.transferStarted) {
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

        if (data.offerRefused) {
            UI.showAlert(NameGenerator.getDisplayName(remoteId) + ' is unreachable.', 'error');
            UI.updateTransferSpeed(0, remoteId);
            closeConnection(remoteId);
            return;
        }

        if (data.offer) {
            const existingConn = connections.get(remoteId);
            if (existingConn && existingConn.peerConnection &&
                existingConn.peerConnection.connectionState !== 'closed' &&
                existingConn.peerConnection.connectionState !== 'failed') {
                console.warn('Declining concurrent offer from ' + remoteId);
                SocketService.sendSignal('decline', {
                    targetDeviceId: remoteId,
                    senderDeviceId: deviceId,
                    decline: true
                });
                return;
            }

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
                // The placeholder occupies the transfer slot NOW (before the user even
                // answers the dialog), so the device list must re-render the peer's
                // "Sending…" state instead of waiting for handleOffer.
                notifyActiveTransfersChanged();
            }
            cancelPendingHide(remoteId); // don't let a prior transfer's hide timer hide this one

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
                UI.updateTransferSpeed(0, remoteId);
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
                UI.updateProgress(remoteId, 0, 'Receiver accepted — establishing connection…');
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
            notifyActiveTransfersChanged();
        }
        // A new batch replaces the previous batch's received-files entries for THIS device
        // only — entries from other devices (concurrent receivers share the panel) are
        // preserved, and every replaced entry's object URL is revoked.
        conn.batchId = ++receivedFilesBatchSeq;
        clearReceivedFilesForRemote(remoteId, conn.batchId);
        conn.receivedFiles = null;
        conn.failedFiles = null;
        conn.fileOutcomes = null;
        conn.expectedFiles = files.length;
        conn.finalAckSent = false;
        conn.batchFiles = files;
        conn.totalBytes = files.reduce((s, f) => s + (f.size || 0), 0);
        conn.totalDoneBytes = 0;
        conn.currentFile = null;
        conn.currentFileIndex = -1;
        conn.currentChunks = [];
        conn.currentReceived = 0;
        conn.lastAckSent = 0;
        conn.lastUiUpdate = 0;
        conn.pendingFinalize = 0;
        conn.doneReceived = false;
        conn.aborted = false;
        stopFinalizeAlive(conn);

        // If a stale peerConnection exists from a previous attempt, drop it cleanly
        // (keep pendingCandidates — they were buffered for THIS offer)
        if (conn.peerConnection) {
            try { conn.peerConnection.close(); } catch (e) { /* ignore */ }
            conn.peerConnection = null;
        }

        try {
            conn.peerConnection = new RTCPeerConnection({ iceServers: ICE_SERVERS });
            wireIceCandidate(conn, remoteId, deviceId, () => reportConnectionType(conn.peerConnection, remoteId));

            conn.peerConnection.ondatachannel = (e) => {
                conn.dataChannel = e.channel;
                conn.dataChannel.binaryType = 'arraybuffer';
                conn.transferStartTime = Date.now();
                resetStallTimer(conn, remoteId); // arm watchdog from the moment channel opens

                e.channel.onmessage = (msg) => handleIncomingMessage(conn, remoteId, msg.data);
                e.channel.onerror = (err) => {
                    UI.showAlert('Receive channel error: ' + (err.message || err.type || 'unknown'), 'error');
                    closeConnection(remoteId);
                };
            };

            await conn.peerConnection.setRemoteDescription(new RTCSessionDescription(data.offer));
            for (const candidate of conn.pendingCandidates) {
                // Isolate each candidate: one malformed/duplicate entry must not abort the whole
                // accept flow — the remaining valid candidates can still establish the connection.
                try {
                    await conn.peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
                } catch (e) {
                    console.warn('Failed to flush queued ICE (receiver):', e);
                }
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

    isTransferring: (peerId) => connections.has(peerId),

    cancelAll: () => {
        for (const id of Array.from(connections.keys())) closeConnection(id);
    },

    onTransferComplete: null,
    onActiveTransfersChanged: null
};
