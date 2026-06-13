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

    updateDeviceList: (devices, currentDeviceId, onSend, canSend, isTransferring) => {
        const listDiv = document.getElementById('deviceList');
        const otherDevices = devices.filter(d => d !== currentDeviceId);

        if (otherDevices.length === 0) {
            listDiv.innerHTML = '<div class="empty-state"><div class="empty-state-icon">🔍</div><p>No devices connected yet</p></div>';
            return;
        }

        listDiv.innerHTML = '';
        otherDevices.forEach(device => {
            const transferring = isTransferring && isTransferring(device);

            const div = document.createElement('div');
            div.className = 'device-item';

            const top = document.createElement('div');
            top.className = 'device-item-top';

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
            sendBtn.textContent = transferring ? '⏳ Sending…' : '📤 Send';
            sendBtn.disabled = !canSend || transferring;
            sendBtn.title = transferring
                ? 'Transfer in progress'
                : (canSend ? 'Send selected file(s)' : 'Select file(s) first');
            sendBtn.onclick = (e) => {
                e.stopPropagation();
                onSend(device);
            };

            top.appendChild(info);
            top.appendChild(sendBtn);
            div.appendChild(top);

            const progress = document.createElement('div');
            progress.className = 'device-progress';
            progress.id = 'progress-' + device;
            progress.style.display = 'none';
            progress.innerHTML =
                '<div class="progress-bar-sm"><div class="progress-fill-sm" id="progress-fill-' + device + '">0%</div></div>' +
                '<span class="progress-label" id="progress-text-' + device + '"></span>' +
                '<span class="progress-label progress-speed" id="progress-speed-' + device + '"></span>';
            div.appendChild(progress);

            listDiv.appendChild(div);
        });
    },

    updateProgress: (peerId, percent, text) => {
        const clamped = Math.min(Math.max(percent, 0), 100);

        const peerProgress = document.getElementById('progress-' + peerId);
        if (peerProgress) {
            const peerFill = document.getElementById('progress-fill-' + peerId);
            const peerText = document.getElementById('progress-text-' + peerId);
            peerProgress.style.display = 'block';
            if (peerFill) {
                peerFill.style.width = clamped + '%';
                peerFill.textContent = clamped + '%';
            }
            if (peerText) peerText.textContent = text;
        }
    },

    hideProgress: (peerId) => {
        if (!peerId) return;
        const peerProgress = document.getElementById('progress-' + peerId);
        if (peerProgress) peerProgress.style.display = 'none';
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

    updateTransferSpeed: (bytesPerSecond, peerId) => {
        if (!peerId) return;
        const el = document.getElementById('progress-speed-' + peerId);
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