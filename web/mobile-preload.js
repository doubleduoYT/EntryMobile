(() => {
    const pending = new Map();
    const listeners = new Map();
    let sequence = 0;
    const id = () => `em-${Date.now()}-${++sequence}`;

    function request(start) {
        return new Promise((resolve, reject) => {
            const requestId = id();
            pending.set(requestId, { resolve, reject });
            start(requestId);
        });
    }

    window.__entryMobileResolve = (requestId, ok, payload) => {
        const item = pending.get(requestId);
        if (!item) return;
        pending.delete(requestId);
        if (ok) item.resolve(payload);
        else item.reject(payload instanceof Error ? payload : new Error(payload?.message || String(payload)));
    };

    window.__entryMobileEmit = (channel, payload) => {
        (listeners.get(channel) || []).slice().forEach((listener) => {
            try { listener({}, payload); } catch (e) { console.error(e); }
        });
    };

    window.ipcInvoke = (channel, ...args) => request((requestId) =>
        Android.invoke(requestId, channel, JSON.stringify(args))
    );

    window.ipcSend = (channel, ...args) => {
        // Electron send() is fire-and-forget. We still invoke the native side so
        // supported commands work; unsupported desktop-only commands are ignored.
        request((requestId) => Android.invoke(requestId, channel, JSON.stringify(args))).catch(() => {});
    };

    window.ipcListen = (channel, listener) => {
        const list = listeners.get(channel) || [];
        list.push(listener);
        listeners.set(channel, list);
        return {
            removeListener(name, fn) {
                const current = listeners.get(name) || [];
                listeners.set(name, current.filter((x) => x !== fn));
            },
        };
    };

    window.dialog = {
        showOpenDialog(options = {}) {
            return request((requestId) => Android.openDialog(requestId, JSON.stringify(options)));
        },
        showSaveDialog(options = {}) {
            return request((requestId) => Android.saveDialog(requestId, JSON.stringify(options)));
        },
        showMessageBox(options = {}) {
            const yes = window.confirm(options.message || options.title || 'EntryMobile');
            return Promise.resolve({ response: yes ? 0 : 1 });
        },
        showMessageBoxSync(options = {}) {
            return window.confirm(options.message || options.title || 'EntryMobile') ? 0 : 1;
        },
    };

    window.getSharedObject = () => ({
        version: '2.1.35',
        appName: 'entry-mobile',
        roomIds: [],
        updateCheckUrl: 'https://playentry.org',
        moduleResourceUrl: 'http://localhost:23518/modules',
        remoteModuleResourceUrl: 'http://playentry.org/modules',
    });

    window.onPageLoaded = (callback) => {
        if (document.readyState === 'complete') queueMicrotask(callback);
        else window.addEventListener('load', callback, { once: true });
    };

    window.onLoadProjectFromMain = (callback) => {
        const listener = async (_event, uri) => callback(window.ipcInvoke('loadProject', uri));
        const list = listeners.get('loadProjectFromMain') || [];
        list.push(listener);
        listeners.set('loadProjectFromMain', list);
    };

    window.openEntryWebPage = () => window.open('https://playentry.org/download/offline', '_blank');
    window.openHardwarePage = () => alert('Entry Hardware는 Android 포팅 작업 중이야.');
    window.weightsPath = () => '../../../node_modules/entry-js/weights';
    window.getEntryjsPath = () => '../../../node_modules/entry-js';
    window.getAppPathWithParams = (...parts) => `../../../${parts.join('/')}`;
    window.getLang = (key) => (window.Lang && window.Lang[key]) || key;
    window.checkPermission = (type) => window.ipcInvoke('checkPermission', type);
    window.getPapagoHeaderInfo = () => window.ipcInvoke('getPapagoHeaderInfo');
    window.isOffline = true;
    window.isOsx = false;

    // Minimal compatibility for code that expects Electron-ish process fields.
    if (!window.process) window.process = {};
    window.process.platform = 'android';
    window.process.env = window.process.env || { NODE_ENV: 'production' };
    window.process.resourcesPath = '../../../node_modules/entry-js';
})();
