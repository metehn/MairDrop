const UI = {
    updateConnectionStatus: (connected) => {
        const statusEl = document.getElementById('connectionStatus');
        if (connected) {
            statusEl.innerHTML = '<div class="status-dot"></div><span>🟢 Bağlı</span>';
            statusEl.className = 'connection-status connected';
        } else {
            statusEl.innerHTML = '<div class="status-dot"></div><span>🔴 Bağlantı Kesildi</span>';
            statusEl.className = 'connection-status disconnected';
        }
    },

    updateDeviceList: (devices, currentDeviceId, targetDeviceId, onSelect) => {
        const listDiv = document.getElementById('deviceList');
        const otherDevices = devices.filter(d => d !== currentDeviceId);

        if (otherDevices.length === 0) {
            listDiv.innerHTML = '<div class="empty-state"><div class="empty-state-icon">🔍</div><p>No devices connected yet</p></div>';
            return;
        }

        listDiv.innerHTML = '';
        otherDevices.forEach(device => {
            const div = document.createElement('div');
            div.className = `device-item ${device === targetDeviceId ? 'selected' : ''}`;
            div.innerHTML = `<div><strong>🖥️ ${device}</strong><small style="display: block; opacity: 0.7;">Çevrimiçi</small></div><div>✓</div>`;
            div.onclick = () => onSelect(device);
            listDiv.appendChild(div);
        });
    },

    updateProgress: (percent, text) => {
        const fill = document.getElementById('progressFill');
        const progressText = document.getElementById('progressText');
        const container = document.getElementById('progressContainer');

        container.style.display = 'block';
        fill.style.width = Math.min(percent, 100) + '%';
        fill.textContent = Math.min(percent, 100) + '%';
        progressText.textContent = text;
    },

    showAlert: (message, type) => {
        const container = document.getElementById('alertContainer');
        const alert = document.createElement('div');
        alert.className = `alert ${type}`;
        alert.textContent = message;
        alert.style.display = 'block';
        container.appendChild(alert);
        setTimeout(() => alert.remove(), 5000);
    },

    formatFileSize: (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
};