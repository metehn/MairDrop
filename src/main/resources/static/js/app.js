// --- Public Room Dialog ---
const RoomDialog = {
    currentCode: null,
    qrInstance: null,

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
                this.evaluateJoinBtn();
            });
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Backspace' && !input.value && i > 0) {
                    inputs[i - 1].value = '';
                    inputs[i - 1].focus();
                    this.evaluateJoinBtn();
                }
            });
            input.addEventListener('paste', (e) => {
                e.preventDefault();
                const text = (e.clipboardData.getData('text') || '').replace(/[^a-zA-Z]/g, '').toUpperCase().substring(0, 5);
                inputs.forEach((inp, j) => { inp.value = text[j] || ''; });
                const lastFilled = Math.min(text.length, inputs.length) - 1;
                if (lastFilled >= 0) inputs[lastFilled].focus();
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
        if (code.length === 5) SocketService.joinRoom(code);
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
        const filled = Array.from(document.querySelectorAll('.room-char-input')).every(i => i.value.length === 1);
        document.getElementById('roomJoinBtn').disabled = !filled;
    },

    handleEvent(event) {
        switch (event.type) {
            case 'ROOM_CREATED':
                this.setRoom(event.roomCode);
                this.open();
                DiscoveryManager.onRoomJoined();
                break;
            case 'ROOM_JOINED':
                this.setRoom(event.roomCode);
                this.close();
                DiscoveryManager.onRoomJoined();
                break;
            case 'ROOM_INVALID':
                UI.showAlert('Room not found. Check the code and try again.', 'error');
                break;
            case 'ROOM_LEFT':
                this.clearRoom();
                this.close();
                DiscoveryManager.onRoomLeft();
                break;
        }
    }
};

const generateDeviceId = () => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return 'dev_' + crypto.randomUUID().replace(/-/g, '').substring(0, 9);
    }
    return 'dev_' + Math.random().toString(36).substr(2, 9);
};

let currentDeviceId = localStorage.getItem('deviceId') || generateDeviceId();
localStorage.setItem('deviceId', currentDeviceId);
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

const onFilesSelected = (files) => {
    if (!files || files.length === 0) return;
    selectedFiles = Array.from(files);

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
onTransferComplete = () => resetSelection();

// Keep the device list's per-device "Sending..." state in sync with active transfers.
onActiveTransfersChanged = () => refreshDeviceList();

// --- Per-badge discovery visibility ---
const DiscoveryManager = {
    netHidden: false,
    roomHidden: false,
    netHiddenByRoom: false, // true only when WE auto-hid the network on room join

    init() {
        document.getElementById('netEye').addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleNetwork();
        });
        document.getElementById('roomEye').addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleRoom();
        });
    },

    toggleNetwork() {
        this.netHiddenByRoom = false; // user is taking manual control; don't auto-restore on room leave
        if (this.netHidden) {
            SocketService.showOnNetwork();
        } else {
            SocketService.hideFromNetwork();
        }
    },

    toggleRoom() {
        if (this.roomHidden) {
            SocketService.showInRoom();
        } else {
            SocketService.hideFromRoom();
        }
    },

    onRoomJoined() {
        this.roomHidden = false;
        this._updateRoomBadge();
        if (!this.netHidden) {
            this.netHiddenByRoom = true;
            SocketService.hideFromNetwork();
        } else {
            this.netHiddenByRoom = false; // user already hid manually — not our hide to undo
        }
    },

    onRoomLeft() {
        this.roomHidden = false;
        this._updateRoomBadge();
        if (this.netHiddenByRoom) {
            this.netHiddenByRoom = false;
            SocketService.showOnNetwork();
        }
    },

    handleEvent(event) {
        switch (event.type) {
            case 'NETWORK_HIDDEN':
                this.netHidden = true;
                this._updateNetBadge();
                break;
            case 'NETWORK_VISIBLE':
                this.netHidden = false;
                this._updateNetBadge();
                break;
            case 'ROOM_HIDDEN':
                this.roomHidden = true;
                this._updateRoomBadge();
                break;
            case 'ROOM_VISIBLE':
                this.roomHidden = false;
                this._updateRoomBadge();
                break;
            case 'ROOM_INVALID':
                // Pending room expired — clear room UI
                this.roomHidden = false;
                this._updateRoomBadge();
                RoomDialog.clearRoom();
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
const urlParams = new URLSearchParams(window.location.search);
if (urlParams.has('room_id')) {
    sessionStorage.setItem('room_id', urlParams.get('room_id').toUpperCase());
    window.history.replaceState({}, '', window.location.pathname);
}

// --- WebSocket / device list ---
SocketService.connect(currentDeviceId, {
    onDevicesUpdate: (devices) => {
        latestDevices = devices;
        refreshDeviceList();
        if (RoomDialog.currentCode && devices.filter(d => d !== currentDeviceId).length > 0) {
            RoomDialog.close();
        }
    },
    onSignal: (data) => WebRTCService.handleSignal(data, currentDeviceId),
    onRoomEvent: (event) => RoomDialog.handleEvent(event),
    onVisibilityEvent: (event) => DiscoveryManager.handleEvent(event)
});

// --- File picker ---
fileInput.addEventListener('change', (e) => onFilesSelected(e.target.files));
clearFilesBtn.addEventListener('click', resetSelection);

// --- Drag & drop ---
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
