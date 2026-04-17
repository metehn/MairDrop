const UI = {
    updateConnectionStatus: (connected) => {
        const statusEl = document.getElementById('connectionStatus');
        if (connected) {
            statusEl.innerHTML = '<div class="status-dot"></div><span>🟢 Connected</span>';
            statusEl.className = 'connection-status connected';
        } else {
            statusEl.innerHTML = '<div class="status-dot"></div><span>🔴 Connection Lost</span>';
            statusEl.className = 'connection-status disconnected';
        }
    },

    updateDeviceList: (devices, currentDeviceId, hasFile, onSend, isTransferring) => {
        const listDiv = document.getElementById('deviceList');
        const otherDevices = devices.filter(d => d !== currentDeviceId);

        if (otherDevices.length === 0) {
            listDiv.innerHTML = '<div class="empty-state"><div class="empty-state-icon">🔍</div><p>No devices connected yet</p></div>';
            return;
        }

        listDiv.innerHTML = '';
        otherDevices.forEach(device => {
            const transferring = isTransferring(device);
            const div = document.createElement('div');
            div.className = 'device-item';
            div.innerHTML = `
                <div class="device-item-top">
                    <div><strong>🖥️ ${device}</strong><small style="display:block;opacity:0.7;">Online</small></div>
                    <button class="btn-send" ${(hasFile && !transferring) ? '' : 'disabled'}>
                        ${transferring ? '⏳ Sending...' : '📤 Send'}
                    </button>
                </div>
                <div class="device-progress" id="progress-${device}" style="display:none">
                    <div class="progress-bar-sm">
                        <div class="progress-fill-sm" id="progress-fill-${device}">0%</div>
                    </div>
                    <span class="progress-label" id="progress-text-${device}"></span>
                </div>`;
            div.querySelector('.btn-send').onclick = () => onSend(device);
            listDiv.appendChild(div);
        });
    },

    updateProgress: (peerId, percent, text) => {
        const container = document.getElementById(`progress-${peerId}`);
        const fill = document.getElementById(`progress-fill-${peerId}`);
        const label = document.getElementById(`progress-text-${peerId}`);
        if (!container) return;
        container.style.display = 'block';
        fill.style.width = Math.min(percent, 100) + '%';
        fill.textContent = Math.min(percent, 100) + '%';
        label.textContent = text;
    },

    showConfirm: (message, onAccept, onDecline) => {
        const overlay = document.getElementById('modalOverlay');
        document.getElementById('modalMessage').textContent = message;
        overlay.style.display = 'flex';

        const accept = document.getElementById('modalAccept');
        const decline = document.getElementById('modalDecline');

        const cleanup = () => {
            overlay.style.display = 'none';
            accept.onclick = null;
            decline.onclick = null;
        };

        accept.onclick = () => { cleanup(); onAccept(); };
        decline.onclick = () => { cleanup(); if (onDecline) onDecline(); };
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
