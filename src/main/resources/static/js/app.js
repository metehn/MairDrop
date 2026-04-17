let currentDeviceId = localStorage.getItem('deviceId') || 'dev_' + Math.random().toString(36).substr(2, 9);
localStorage.setItem('deviceId', currentDeviceId);
document.getElementById('deviceIdSpan').textContent = currentDeviceId;

let selectedFile = null;
let lastDevices = [];

const renderDevices = () => UI.updateDeviceList(
    lastDevices, currentDeviceId, !!selectedFile,
    (id) => WebRTCService.createOffer(id, currentDeviceId, selectedFile),
    WebRTCService.isTransferring
);

WebRTCService.init(renderDevices);

SocketService.connect(currentDeviceId, {
    onDevicesUpdate: (devices) => { lastDevices = devices; renderDevices(); },
    onSignal: (data) => WebRTCService.handleSignal(data, currentDeviceId)
});

document.getElementById('fileInput').addEventListener('change', (e) => {
    selectedFile = e.target.files[0];
    if (selectedFile) {
        document.getElementById('fileName').textContent = selectedFile.name;
        document.getElementById('fileSize').textContent = UI.formatFileSize(selectedFile.size);
        document.getElementById('fileInfo').style.display = 'block';
    }
    renderDevices();
});