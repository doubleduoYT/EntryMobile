(() => {
    const pending = new Map();
    const listeners = new Map();
    let sequence = 0;
    const requestId = () => `em-${Date.now()}-${++sequence}`;
    const sharedObject = {
        version: '2.1.35',
        appName: 'entry-mobile',
        roomIds: [],
        updateCheckUrl: 'https://playentry.org',
        moduleResourceUrl: 'http://localhost:23518/modules',
        remoteModuleResourceUrl: 'http://playentry.org/modules',
    };

    function request(start) {
        return new Promise((resolve, reject) => {
            const id = requestId();
            pending.set(id, { resolve, reject });
            start(id);
        });
    }

    window.__entryMobileResolve = (id, ok, payload) => {
        const item = pending.get(id);
        if (!item) return;
        pending.delete(id);
        if (ok) item.resolve(payload);
        else item.reject(payload instanceof Error ? payload : new Error(payload?.message || String(payload)));
    };

    window.__entryMobileEmit = (channel, payload) => {
        (listeners.get(channel) || []).slice().forEach((listener) => {
            try { listener({}, payload); } catch (e) { console.error(e); }
        });
    };

    window.ipcInvoke = (channel, ...args) => request((id) =>
        Android.invoke(id, channel, JSON.stringify(args))
    );

    window.ipcSend = (channel, ...args) => {
        request((id) => Android.invoke(id, channel, JSON.stringify(args))).catch(() => {});
    };

    window.ipcListen = (channel, listener) => {
        const list = listeners.get(channel) || [];
        list.push(listener);
        listeners.set(channel, list);
        return {
            removeListener(name, fn) {
                const current = listeners.get(name) || [];
                listeners.set(name, current.filter((item) => item !== fn));
            },
        };
    };

    // The 2.1.35 renderer declares this API but does not currently use it.
    // Android's JavaScript bridge is asynchronous, so keep a safe compatibility stub.
    window.sendSync = () => null;

    window.dialog = {
        showOpenDialog(options = {}) {
            return request((id) => Android.openDialog(id, JSON.stringify(options)));
        },
        showSaveDialog(options = {}) {
            return request((id) => Android.saveDialog(id, JSON.stringify(options)));
        },
        showMessageBox(options = {}) {
            const accepted = window.confirm(options.message || options.title || 'EntryMobile');
            return Promise.resolve({ response: accepted ? 0 : 1 });
        },
        showMessageBoxSync(options = {}) {
            return window.confirm(options.message || options.title || 'EntryMobile') ? 0 : 1;
        },
    };

    window.getSharedObject = () => sharedObject;

    // Desktop Entry rebuilds the native menu after a language change.
    // There is no native Electron menu on Android, but the renderer calls this unconditionally.
    window.initNativeMenu = () => {};

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
    window.getLang = (key) => {
        const value = String(key).split('.').reduce((obj, part) =>
            obj && Object.prototype.hasOwnProperty.call(obj, part) ? obj[part] : undefined,
        window.Lang || {});
        return value === undefined ? key : value;
    };
    window.checkPermission = (type) => window.ipcInvoke('checkPermission', type);
    window.getPapagoHeaderInfo = () => window.ipcInvoke('getPapagoHeaderInfo');
    window.isOffline = true;
    window.isOsx = false;

    if (!window.process) window.process = {};
    window.process.platform = 'android';
    window.process.env = window.process.env || { NODE_ENV: 'production' };
    window.process.env.NODE_ENV = window.process.env.NODE_ENV || 'production';
    window.process.resourcesPath = '../../../node_modules/entry-js';
})();
