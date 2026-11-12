// --- Public Room Dialog ---
const RoomDialog = {
    currentCode: null,
    qrInstance: null,
    pendingJoin: null, // { code } while a join request for that code is still in flight

    init() {
        document.getElementById('openRoomBtn').addEventListener('click', () => this.onOpenClick());
        document.getElementById('roomDiscBadge').addEventListener('click', () => this.open());
        document.getElementById('roomModalBackdrop').addEventListener('click', () => this.close());
        document.getElementById('roomCloseBtn').addEventListener('click', () => this.close());
        document.getElementById('roomLeaveBtn').addEventListener('click', () => this.onLeave());
        document.getElementById('roomJoinBtn').addEventListener('click', () => this.onJoin());

        const inputs = document.querySelectorAll('.room-char-input');
        inputs.forEach((input, i) => {
            input.addEventListener('input', () => {
                input.value = input.value.replace(/[^a-zA-Z]/g, '').toUpperCase();
                if (input.value && i < inputs.length - 1) inputs[i + 1].focus();
                this.clearPendingIfCodeChanged();
                this.evaluateJoinBtn();
            });
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Backspace' && !input.value && i > 0) {
                    inputs[i - 1].value = '';
                    inputs[i - 1].focus();
                    this.clearPendingIfCodeChanged();
                    this.evaluateJoinBtn();
                }
            });
            input.addEventListener('paste', (e) => {
                e.preventDefault();
                const text = (e.clipboardData.getData('text') || '').replace(/[^a-zA-Z]/g, '').toUpperCase().substring(0, 5);
                inputs.forEach((inp, j) => { inp.value = text[j] || ''; });
                const lastFilled = Math.min(text.length, inputs.length) - 1;
                if (lastFilled >= 0) inputs[lastFilled].focus();
                this.clearPendingIfCodeChanged();
                this.evaluateJoinBtn();
            });
        });
    },

    onOpenClick() {
        if (this.currentCode) {
            this.open();
        } else {
            SocketService.createRoom();
        }
    },

    open() {
        document.getElementById('roomModal').style.display = 'flex';
        this.clearInputs();
    },

    close() {
        document.getElementById('roomModal').style.display = 'none';
        this.clearInputs();
    },

    onLeave() {
        SocketService.leaveRoom();
        this.close();
    },

    onJoin() {
        const code = Array.from(document.querySelectorAll('.room-char-input')).map(i => i.value).join('');
        if (code.length !== 5) return;
        // A join for THIS code is already in flight — ignore duplicate submits. The 5s
        // fallback below re-enables the button when the server reply is lost, so without
        // this guard a second click would fire a second join for the same code.
        if (this.pendingJoin && this.pendingJoin.code === code) return;
        this.pendingJoin = { code };
        document.getElementById('roomJoinBtn').disabled = true;
        SocketService.joinRoom(code);
        // If the server reply is lost (reconnect race, dropped frame), don't leave the
        // button disabled forever — restore it once the code inputs are filled again.
        setTimeout(() => {
            if (this.pendingJoin && this.pendingJoin.code === code) this.pendingJoin = null;
            if (this.currentCode !== code) this.evaluateJoinBtn();
        }, 5000);
    },

    // Editing the inputs away from the in-flight code counts as a deliberate cancel:
    // allow a new join to fire immediately instead of blocking on the stale one.
    clearPendingIfCodeChanged() {
        if (!this.pendingJoin) return;
        const code = Array.from(document.querySelectorAll('.room-char-input')).map(i => i.value).join('');
        if (code !== this.pendingJoin.code) this.pendingJoin = null;
    },

    setRoom(code) {
        this.currentCode = code;
        sessionStorage.setItem('room_id', code);

        document.getElementById('roomCodeDisplay').textContent = code;
        document.getElementById('roomDiscCode').textContent = code;
        document.getElementById('roomDiscBadge').style.display = 'inline-flex';

        const qrContainer = document.getElementById('roomQrCode');
        qrContainer.innerHTML = '';
        if (this.qrInstance) { this.qrInstance = null; }
        const url = `${location.origin}${location.pathname}?room_id=${code}`;
        this.qrInstance = new QRCode(qrContainer, { text: url, width: 150, height: 150, correctLevel: QRCode.CorrectLevel.L });
    },

    clearRoom() {
        this.currentCode = null;
        sessionStorage.removeItem('room_id');
        document.getElementById('roomDiscBadge').style.display = 'none';
        document.getElementById('roomCodeDisplay').textContent = '';
        document.getElementById('roomQrCode').innerHTML = '';
        this.qrInstance = null;
    },

    clearInputs() {
        document.querySelectorAll('.room-char-input').forEach(i => i.value = '');
        document.getElementById('roomJoinBtn').disabled = true;
    },

    evaluateJoinBtn() {
        const inputs = Array.from(document.querySelectorAll('.room-char-input'));
        const filled = inputs.every(i => i.value.length === 1);
        // A join for the currently typed code is still in flight: onJoin would silently ignore
        // the click, so keep the button disabled instead of showing an enabled no-op.
        const pending = this.pendingJoin && this.pendingJoin.code === inputs.map(i => i.value).join('');
        document.getElementById('roomJoinBtn').disabled = !filled || pending;
    },

    handleEvent(event) {
        switch (event.type) {
            case 'ROOM_CREATED':
                this.setRoom(event.roomCode);
                this.open();
                DiscoveryManager.onRoomJoined();
                break;
            case 'ROOM_JOINED':
                if (this.pendingJoin && (!event.roomCode || event.roomCode === this.pendingJoin.code)) {
                    this.pendingJoin = null;
                }
                this.setRoom(event.roomCode);
                this.close();
                DiscoveryManager.onRoomJoined();
                break;
            case 'ROOM_INVALID': {
                if (this.pendingJoin && (!event.roomCode || event.roomCode === this.pendingJoin.code)) {
                    this.pendingJoin = null;
                }
                this.evaluateJoinBtn();
                // If the invalid code is the room we believe we're in (an auto-rejoin of an expired
                // room after reconnect), clear the stale state so we stop retrying it. A different
                // code is just a mistyped manual join — show the error and keep our current room.
                const saved = sessionStorage.getItem('room_id');
                if (event.roomCode && (event.roomCode === this.currentCode || event.roomCode === saved)) {
                    this.clearRoom();
                    DiscoveryManager.onRoomLeft();
                    UI.showAlert('Room has expired. Please create or join a new room.', 'error');
                } else {
                    UI.showAlert('Room not found. Check the code and try again.', 'error');
                }
                break;
            }
            case 'ROOM_RATE_LIMITED':
                // The in-flight join was rejected — allow a new attempt after the cooldown.
                this.pendingJoin = null;
                this.evaluateJoinBtn();
                // Too many failed join attempts from this client — keep current room state intact.
                UI.showAlert('Too many attempts. Please wait a moment and try again.', 'error');
                break;
            case 'ROOM_CREATE_LIMITED':
                this.evaluateJoinBtn();
                // Too many rooms created from this client — re-evaluate the join button and
                // surface the backend-provided message.
                UI.showAlert(event.message || 'Too many rooms created. Please wait a moment and try again.', 'error');
                break;
            case 'ROOM_LEFT':
                this.clearRoom();
                this.close();
                DiscoveryManager.onRoomLeft();
                break;
        }
    }
};

// Device ids must match the backend's /^dev_[0-9a-f]+$/ validation: lowercase hex only.
const DEVICE_ID_PATTERN = /^dev_[0-9a-f]+$/;

const generateDeviceId = () => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return 'dev_' + crypto.randomUUID().replace(/-/g, '').substring(0, 9);
    }
    // Fallback must stay lowercase hex too — base-36 output contains g-z and would be
    // rejected by the backend.
    let hex = '';
    while (hex.length < 9) {
        hex += Math.floor(Math.random() * 0x100000000).toString(16).padStart(8, '0');
    }
    return 'dev_' + hex.substring(0, 9);
};

const generateToken = () => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID().replace(/-/g, '');
    }
    return Math.random().toString(36).substr(2) + Math.random().toString(36).substr(2);
};

// Per-tab identity. sessionStorage keeps the id/token stable across reloads of THIS tab but gives
// every tab its own — two tabs sharing one device id would both answer the same incoming transfer,
// and the tab the user ignores auto-declines it out from under the one that accepted. The token is
// a secret this tab keeps so no other client on the network can re-register under its device id.
let currentDeviceId = sessionStorage.getItem('deviceId');
if (!currentDeviceId || !DEVICE_ID_PATTERN.test(currentDeviceId)) {
    // Regenerate ids that predate the hex-only backend validation (e.g. old base-36
    // fallback ids) so they pass /^dev_[0-9a-f]+$/ on registration.
    currentDeviceId = generateDeviceId();
    sessionStorage.setItem('deviceId', currentDeviceId);
}
let deviceToken = sessionStorage.getItem('deviceToken');
if (!deviceToken) {
    deviceToken = generateToken();
    sessionStorage.setItem('deviceToken', deviceToken);
}
// Show the human-readable name so the header matches what other peers see for me.
document.getElementById('deviceIdSpan').textContent = NameGenerator.getDisplayName(currentDeviceId);

let selectedFiles = [];
let latestDevices = [];

const fileInput = document.getElementById('fileInput');
const fileInfo = document.getElementById('fileInfo');
const fileInputWrapper = document.getElementById('fileInputWrapper');
const sendHint = document.getElementById('sendHint');
const clearFilesBtn = document.getElementById('clearFilesBtn');

const refreshDeviceList = () => {
    UI.updateDeviceList(latestDevices, currentDeviceId, sendToDevice, selectedFiles.length > 0, WebRTCService.isTransferring);
};

const MAX_FILE_SIZE_BYTES = 500 * 1024 * 1024; // 500MB limit to prevent browser RAM exhaustion
const MAX_FILES_PER_BATCH = 100; // keep multi-file offer metadata well below the server's 1MB message limit

const onFilesSelected = (files) => {
    if (!files || files.length === 0) return;
    const fileListArray = Array.from(files);
    if (fileListArray.length > MAX_FILES_PER_BATCH) {
        UI.showAlert(`Too many files selected — maximum ${MAX_FILES_PER_BATCH} files per batch.`, 'error');
        resetSelection();
        return;
    }
    const oversizedFile = fileListArray.find(f => f.size > MAX_FILE_SIZE_BYTES);
    if (oversizedFile) {
        UI.showAlert(`File "${oversizedFile.name}" exceeds the maximum allowed size (500 MB).`, 'error');
        resetSelection();
        return;
    }
    selectedFiles = fileListArray;

    const countLabel = document.getElementById('fileCountLabel');
    countLabel.textContent = selectedFiles.length === 1
        ? 'Selected file'
        : `Selected files (${selectedFiles.length})`;

    const list = document.getElementById('fileList');
    list.innerHTML = '';
    selectedFiles.forEach((f) => {
        const li = document.createElement('li');
        li.style.cssText = 'padding:8px 0;display:flex;justify-content:space-between;align-items:center;gap:10px;border-bottom:1px solid #f1f3f5;';

        const nameSpan = document.createElement('span');
        nameSpan.textContent = '📎 ' + f.name;
        nameSpan.style.cssText = 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0;';
        nameSpan.title = f.name;

        const sizeSpan = document.createElement('span');
        sizeSpan.textContent = UI.formatFileSize(f.size);
        sizeSpan.style.cssText = 'color:#6c757d;font-size:0.88em;white-space:nowrap;';

        li.appendChild(nameSpan);
        li.appendChild(sizeSpan);
        list.appendChild(li);
    });

    const totalSize = selectedFiles.reduce((s, f) => s + f.size, 0);
    document.getElementById('fileSize').textContent = UI.formatFileSize(totalSize);
    fileInfo.style.display = 'block';
    sendHint.style.display = 'block';
    refreshDeviceList();
};

const resetSelection = () => {
    selectedFiles = [];
    fileInput.value = '';
    fileInfo.style.display = 'none';
    sendHint.style.display = 'none';
    refreshDeviceList();
};

const sendToDevice = (targetId) => {
    if (selectedFiles.length === 0) {
        UI.showAlert('Please select a file first', 'info');
        return;
    }
    if (!latestDevices.includes(targetId)) {
        UI.showAlert('Target device is no longer online', 'error');
        return;
    }
    // Hide hint while transfer is in progress
    sendHint.style.display = 'none';
    WebRTCService.createOffer(targetId, currentDeviceId, selectedFiles);
};

// Make the WebRTC layer notify us when a send finishes so we can clear state.
// Only reset the shared selection UI when NO transfer is active anywhere — a batch that
// just finished to one device must not clear files being sent to a different device.
WebRTCService.onTransferComplete = () => {
    if (WebRTCService.activeTransfers() === 0) resetSelection();
};

// Keep the device list's per-device "Sending..." state in sync with active transfers.
WebRTCService.onActiveTransfersChanged = () => refreshDeviceList();

// --- Per-badge discovery visibility ---
// Persisted so a reload can't silently make a hidden device visible again (auto-rejoin
// would put it back in the room roster, and re-registration back on the network).
const NET_HIDDEN_KEY = 'mairdrop_net_hidden';
const NET_HIDDEN_BY_ROOM_KEY = 'mairdrop_net_hidden_by_room';
const ROOM_HIDDEN_KEY = 'mairdrop_room_hidden';

const DiscoveryManager = {
    netHidden: sessionStorage.getItem(NET_HIDDEN_KEY) === '1',
    roomHidden: sessionStorage.getItem(ROOM_HIDDEN_KEY) === '1',
    // true only when WE auto-hid the network on room join. Persisted so a reload inside the
    // room still knows the hiding was room-caused (leaving later must restore visibility).
    netHiddenByRoom: sessionStorage.getItem(NET_HIDDEN_BY_ROOM_KEY) === '1',

    init() {
        // Hidden from the room before the reload? Drop the saved room so the auto-rejoin in
        // websocket-service.js doesn't silently put us back into the room's roster.
        if (this.roomHidden) sessionStorage.removeItem('room_id');
        this._updateNetBadge();
        this._updateRoomBadge();

        document.getElementById('netEye').addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleNetwork();
        });
        document.getElementById('netEye').addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault(); e.stopPropagation();
                this.toggleNetwork();
            }
        });
        document.getElementById('roomEye').addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleRoom();
        });
        document.getElementById('roomEye').addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault(); e.stopPropagation();
                this.toggleRoom();
            }
        });
    },

    toggleNetwork() {
        // user is taking manual control; don't auto-restore on room leave
        this.netHiddenByRoom = false;
        sessionStorage.removeItem(NET_HIDDEN_BY_ROOM_KEY);
        if (this.netHidden) {
            sessionStorage.removeItem(NET_HIDDEN_KEY);
            SocketService.showOnNetwork();
        } else {
            sessionStorage.setItem(NET_HIDDEN_KEY, '1');
            SocketService.hideFromNetwork();
        }
    },

    toggleRoom() {
        if (this.roomHidden) {
            sessionStorage.removeItem(ROOM_HIDDEN_KEY);
            SocketService.showInRoom();
        } else {
            sessionStorage.setItem(ROOM_HIDDEN_KEY, '1');
            SocketService.hideFromRoom();
        }
    },

    onRoomJoined() {
        this.roomHidden = false;
        sessionStorage.removeItem(ROOM_HIDDEN_KEY);
        this._updateRoomBadge();
        if (!this.netHidden) {
            // Network was visible — auto-hide it and persist WHY. netHiddenByRoom stays
            // untouched when the network was already hidden: keep it true when a previous
            // room auto-hid (switching room→room must still restore on final leave), and
            // false when the user hid manually (their choice, not ours to undo).
            this.netHidden = true;
            this.netHiddenByRoom = true;
            sessionStorage.setItem(NET_HIDDEN_KEY, '1');
            sessionStorage.setItem(NET_HIDDEN_BY_ROOM_KEY, '1');
            SocketService.hideFromNetwork();
            this._updateNetBadge();
        }
    },

    onRoomLeft() {
        this.roomHidden = false;
        sessionStorage.removeItem(ROOM_HIDDEN_KEY);
        this._updateRoomBadge();
        if (this.netHiddenByRoom) {
            this.netHiddenByRoom = false;
            sessionStorage.removeItem(NET_HIDDEN_BY_ROOM_KEY);
            SocketService.showOnNetwork();
        }
    },

    handleEvent(event) {
        switch (event.type) {
            case 'NETWORK_HIDDEN':
                this.netHidden = true;
                sessionStorage.setItem(NET_HIDDEN_KEY, '1');
                this._updateNetBadge();
                break;
            case 'NETWORK_VISIBLE':
                this.netHidden = false;
                sessionStorage.removeItem(NET_HIDDEN_KEY);
                this._updateNetBadge();
                break;
            case 'ROOM_HIDDEN':
                this.roomHidden = true;
                sessionStorage.setItem(ROOM_HIDDEN_KEY, '1');
                this._updateRoomBadge();
                break;
            case 'ROOM_VISIBLE':
                this.roomHidden = false;
                sessionStorage.removeItem(ROOM_HIDDEN_KEY);
                this._updateRoomBadge();
                break;
            case 'ROOM_INVALID':
                // A pending (hidden-from-room) room expired before we could show ourselves back in
                // it. Clear the room UI and run the normal leave path so an auto-hidden network is
                // restored too — otherwise the device is left invisible on the network with no room.
                RoomDialog.clearRoom();
                this.onRoomLeft();
                UI.showAlert('Room has expired. Please create or join a new room.', 'error');
                break;
        }
    },

    _updateNetBadge() {
        const badge = document.getElementById('netBadge');
        const eye = document.getElementById('netEye');
        badge.classList.toggle('disc-active', !this.netHidden);
        badge.classList.toggle('disc-hidden', this.netHidden);
        eye.classList.toggle('slashed', this.netHidden);
    },

    _updateRoomBadge() {
        const badge = document.getElementById('roomDiscBadge');
        const eye = document.getElementById('roomEye');
        badge.classList.toggle('disc-active', !this.roomHidden);
        badge.classList.toggle('disc-hidden', this.roomHidden);
        eye.classList.toggle('slashed', this.roomHidden);
    }
};

// --- Public Room Dialog init ---
RoomDialog.init();
DiscoveryManager.init();

// --- URL param: auto-join room on page load ---
// DiscoveryManager.init() dropped a saved room while hidden from the room; don't resurrect it
// from the URL (that would undo the hiding on the next reload).
const urlParams = new URLSearchParams(window.location.search);
if (urlParams.has('room_id') && sessionStorage.getItem(ROOM_HIDDEN_KEY) !== '1') {
    sessionStorage.setItem('room_id', urlParams.get('room_id').toUpperCase());
    window.history.replaceState({}, '', window.location.pathname);
}

// --- WebSocket / device list ---
SocketService.connect(currentDeviceId, {
    token: deviceToken,
    onDevicesUpdate: (devices) => {
        latestDevices = devices;
        refreshDeviceList();
        if (RoomDialog.currentCode && devices.filter(d => d !== currentDeviceId).length > 0) {
            RoomDialog.close();
        }
    },
    onSignal: (data) => WebRTCService.handleSignal(data, currentDeviceId),
    onRoomEvent: (event) => RoomDialog.handleEvent(event),
    onVisibilityEvent: (event) => DiscoveryManager.handleEvent(event),
    onRegistered: () => {
        // Re-enable the join button in case a previous join's server reply was lost.
        RoomDialog.evaluateJoinBtn();
        if (DiscoveryManager.netHidden) SocketService.hideFromNetwork();
        if (DiscoveryManager.roomHidden) SocketService.hideFromRoom();
    }
});

// --- File picker ---
fileInput.addEventListener('change', (e) => onFilesSelected(e.target.files));
clearFilesBtn.addEventListener('click', resetSelection);

// --- Drag & drop ---
// Prevent default browser drop behavior anywhere on window (navigating away and destroying connections)
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => e.preventDefault());

['dragenter', 'dragover'].forEach(ev => {
    fileInputWrapper.addEventListener(ev, (e) => {
        e.preventDefault();
        e.stopPropagation();
        fileInputWrapper.style.background = '#eef0ff';
    });
});
['dragleave', 'drop'].forEach(ev => {
    fileInputWrapper.addEventListener(ev, (e) => {
        e.preventDefault();
        e.stopPropagation();
        fileInputWrapper.style.background = '';
    });
});
fileInputWrapper.addEventListener('drop', (e) => {
    const files = e.dataTransfer && e.dataTransfer.files;
    if (files && files.length > 0) onFilesSelected(files);
});

// --- Cleanup on tab close ---
window.addEventListener('beforeunload', () => {
    try { WebRTCService.cancelAll(); } catch (e) { /* ignore */ }
});
