let currentDeviceId = localStorage.getItem('deviceId') || 'dev_' + Math.random().toString(36).substr(2, 9);
localStorage.setItem('deviceId', currentDeviceId);
document.getElementById('deviceIdSpan').textContent = currentDeviceId;

let selectedTargetId = null;
let selectedFile = null;

// Start
SocketService.connect(currentDeviceId, {
    onDevicesUpdate: (devices) => UI.updateDeviceList(devices, currentDeviceId, selectedTargetId, (id) => {
        selectedTargetId = id;
        SocketService.refreshDevices(currentDeviceId);// To update the selection
        document.getElementById('sendBtn').disabled = !selectedFile;
    }),
    onSignal: (data) => WebRTCService.handleSignal(data, currentDeviceId)
});

// File selection
document.getElementById('fileInput').addEventListener('change', (e) => {
    selectedFile = e.target.files[0];
    if (selectedFile) {
        document.getElementById('fileName').textContent = selectedFile.name;
        document.getElementById('fileSize').textContent = UI.formatFileSize(selectedFile.size);
        document.getElementById('fileInfo').style.display = 'block';
        document.getElementById('sendBtn').disabled = !selectedTargetId;
    }
});

// Send button
document.getElementById('sendBtn').addEventListener('click', () => {
    if (selectedFile && selectedTargetId) {
        WebRTCService.createOffer(selectedTargetId, currentDeviceId, selectedFile);
    }
});