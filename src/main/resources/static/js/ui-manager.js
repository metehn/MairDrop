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

    updateDeviceList: (devices, currentDeviceId, onSend, canSend) => {
        const listDiv = document.getElementById('deviceList');
        const otherDevices = devices.filter(d => d !== currentDeviceId);

        if (otherDevices.length === 0) {
            listDiv.innerHTML = '<div class="empty-state"><div class="empty-state-icon">🔍</div><p>No devices connected yet</p></div>';
            return;
        }

        listDiv.innerHTML = '';
        otherDevices.forEach(device => {
            const div = document.createElement('div');
            div.className = 'device-item';

            const info = document.createElement('div');

            const strong = document.createElement('strong');
            strong.textContent = '🖥️ ' + NameGenerator.getDisplayName(device);

            const small = document.createElement('small');
            small.style.cssText = 'display: block; opacity: 0.7;';
            small.textContent = 'Online';

            info.appendChild(strong);
            info.appendChild(small);

            const sendBtn = document.createElement('button');
            sendBtn.className = 'device-send-btn';
            sendBtn.textContent = '📤 Send';
            sendBtn.disabled = !canSend;
            sendBtn.title = canSend ? 'Send selected file(s)' : 'Select file(s) first';
            sendBtn.onclick = (e) => {
                e.stopPropagation();
                onSend(device);
            };

            div.appendChild(info);
            div.appendChild(sendBtn);
            listDiv.appendChild(div);
        });
    },

    updateProgress: (percent, text) => {
        const fill = document.getElementById('progressFill');
        const progressText = document.getElementById('progressText');
        const section = document.getElementById('transferSection');

        if (section) section.style.display = 'block';
        const clamped = Math.min(Math.max(percent, 0), 100);
        fill.style.width = clamped + '%';
        fill.textContent = clamped + '%';
        progressText.textContent = text;
    },

    hideProgress: () => {
        const section = document.getElementById('transferSection');
        if (section) section.style.display = 'none';
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

    updateTransferSpeed: (bytesPerSecond) => {
        const el = document.getElementById('transferSpeed');
        if (!el) return;
        el.textContent = bytesPerSecond > 0
            ? `Speed: ${UI.formatFileSize(Math.round(bytesPerSecond))}/s`
            : '';
    },

    formatFileSize: (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
};