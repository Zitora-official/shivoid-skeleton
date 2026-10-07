/**
 * SHIVOID Native Bridge JavaScript Shim
 * Automatically injected into all pages loaded in the SHIVOID Android WebView.
 * Provides a clean Promise-based API over the native ShivoidNative interface.
 */
(function() {
    if (window.Shivoid && window.Shivoid.__initialized) {
        return;
    }

    const pendingCallbacks = new Map();
    let callbackCounter = 1;

    function nextCallbackId() {
        return "cb_" + Date.now() + "_" + (callbackCounter++);
    }

    // Global dispatcher invoked from Android: window.__shivoid_dispatch(resultJson)
    window.__shivoid_dispatch = function(result) {
        try {
            const data = typeof result === "string" ? JSON.parse(result) : result;
            if (!data || !data.callbackId) return;

            const pending = pendingCallbacks.get(data.callbackId);
            if (pending) {
                pendingCallbacks.delete(data.callbackId);
                if (data.success) {
                    pending.resolve(data.data !== undefined ? data.data : data);
                } else {
                    pending.reject(new Error(data.error || "Shivoid native operation failed"));
                }
            }

            // Also emit custom window event if eventName is present
            if (data.eventName) {
                window.dispatchEvent(new CustomEvent("shivoid:" + data.eventName, { detail: data }));
            }
        } catch (e) {
            console.error("[SHIVOID] Dispatch error:", e);
        }
    };

    const hasNative = () => typeof window.ShivoidNative !== "undefined";

    const Shivoid = {
        __initialized: true,
        version: "1.0",
        isNativeAvailable: () => hasNative(),

        // Text-to-Speech
        tts: {
            speak: function(text, options) {
                options = options || {};
                const lang = options.lang || "en";
                const pitch = options.pitch !== undefined ? options.pitch : 1.0;
                const rate = options.rate !== undefined ? options.rate : 1.0;

                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    const started = window.ShivoidNative.speak(text, lang, pitch, rate, cbId);
                    if (!started) {
                        pendingCallbacks.delete(cbId);
                        reject(new Error("Failed to start speech"));
                    }
                });
            },
            stop: function() {
                if (hasNative()) return window.ShivoidNative.stopTTS();
                return false;
            },
            isSpeaking: function() {
                if (hasNative()) return window.ShivoidNative.isSpeaking();
                return false;
            }
        },

        // Vibration / Haptics
        vibrate: function(durationOrPattern) {
            if (!hasNative()) return false;
            if (Array.isArray(durationOrPattern)) {
                return window.ShivoidNative.vibratePattern(JSON.stringify(durationOrPattern), -1);
            } else {
                const duration = typeof durationOrPattern === "number" ? durationOrPattern : 100;
                return window.ShivoidNative.vibrate(duration);
            }
        },
        cancelVibration: function() {
            if (hasNative()) return window.ShivoidNative.cancelVibration();
            return false;
        },

        // Native Notifications
        notification: {
            show: function(title, message, id, channelId) {
                if (!hasNative()) return false;
                const notifId = typeof id === "number" ? id : Math.floor(Math.random() * 10000);
                return window.ShivoidNative.showNotification(title || "SHIVOID", message || "", notifId, channelId || "default");
            },
            cancel: function(id) {
                if (hasNative()) return window.ShivoidNative.cancelNotification(id);
                return false;
            }
        },

        // HTTP & Automate Communication
        http: {
            get: function(url, headers) {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    window.ShivoidNative.httpGet(url, JSON.stringify(headers || {}), cbId);
                });
            },
            post: function(url, body, contentType, headers) {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    const bodyStr = typeof body === "object" ? JSON.stringify(body) : (body || "");
                    const cType = contentType || (typeof body === "object" ? "application/json" : "text/plain");
                    window.ShivoidNative.httpPost(url, bodyStr, cType, JSON.stringify(headers || {}), cbId);
                });
            },
            // Dedicated Automate helper for triggering Automate / LLAR / Tasker HTTP endpoints
            automate: function(endpointUrl, payload) {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    const payloadStr = typeof payload === "object" ? JSON.stringify(payload) : (payload || "{}");
                    window.ShivoidNative.callAutomate(endpointUrl, payloadStr, cbId);
                });
            }
        },

        // Device & Battery Telemetry
        device: {
            getInfo: function() {
                if (!hasNative()) return {};
                try {
                    return JSON.parse(window.ShivoidNative.getDeviceInfo());
                } catch (e) {
                    return {};
                }
            },
            getBattery: function() {
                if (!hasNative()) return {};
                try {
                    return JSON.parse(window.ShivoidNative.getBatteryInfo());
                } catch (e) {
                    return {};
                }
            }
        },

        // Clipboard
        clipboard: {
            copy: function(text) {
                if (hasNative()) return window.ShivoidNative.copyToClipboard(text || "");
                return false;
            },
            read: function() {
                if (hasNative()) return window.ShivoidNative.readClipboard();
                return "";
            }
        },

        // Camera & Media
        camera: {
            takePhoto: function() {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    window.ShivoidNative.takePhoto(cbId);
                });
            }
        },

        // Files
        files: {
            pick: function(mimeType) {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("ShivoidNative not found"));
                    const cbId = nextCallbackId();
                    pendingCallbacks.set(cbId, { resolve, reject });
                    window.ShivoidNative.pickFile(mimeType || "*/*", cbId);
                });
            }
        },

        // External navigation & System apps
        app: {
            openExternal: function(url) {
                if (hasNative()) return window.ShivoidNative.openExternal(url);
                return false;
            },
            launch: function(packageName, fallbackUrl) {
                if (hasNative()) return window.ShivoidNative.launchApp(packageName, fallbackUrl || "");
                return false;
            },
            share: function(text, title) {
                if (hasNative()) return window.ShivoidNative.shareText(text, title || "Share via SHIVOID");
                return false;
            },
            toast: function(message) {
                if (hasNative()) window.ShivoidNative.showToast(message);
            },
            setFullscreen: function(enabled) {
                if (hasNative()) window.ShivoidNative.setFullscreen(!!enabled);
            },
            keepScreenOn: function(enabled) {
                if (hasNative()) window.ShivoidNative.keepScreenOn(!!enabled);
            },
            reload: function() {
                if (hasNative()) window.ShivoidNative.reload();
                else window.location.reload();
            },
            goBack: function() {
                if (hasNative()) window.ShivoidNative.goBack();
                else window.history.back();
            },
            goForward: function() {
                if (hasNative()) window.ShivoidNative.goForward();
                else window.history.forward();
            },
            getLastUrl: function() {
                if (hasNative()) return window.ShivoidNative.getLastUrl();
                return "";
            },
            setLastUrl: function(url) {
                if (hasNative()) window.ShivoidNative.setLastUrl(url);
            }
        }
    };

    window.Shivoid = Shivoid;
    window.SHIVOID = Shivoid; // Alias for convenience
    console.log("[SHIVOID] Native bridge initialized. Access via window.Shivoid or window.ShivoidNative.");

    // Trigger ready event
    window.dispatchEvent(new CustomEvent("shivoid:ready", { detail: { version: Shivoid.version } }));
})();
