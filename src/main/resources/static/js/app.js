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

// --- WebSocket / device list ---
SocketService.connect(currentDeviceId, {
    onDevicesUpdate: (devices) => {
        latestDevices = devices;
        refreshDeviceList();
    },
    onSignal: (data) => WebRTCService.handleSignal(data, currentDeviceId)
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
