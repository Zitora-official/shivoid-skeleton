/**
 * SHI.VOID Native Bridge JavaScript Shim
 * Permanent Native Android Shell & Remote JavaScript Bridge
 * Creator & Developer: SHIVANSH THAKUR
 *
 * Automatically injected into all pages loaded in the SHI.VOID Android WebView.
 * Provides a unified Promise-based API over the native ShivoidNative interface,
 * with graceful desktop browser fallbacks.
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

    // Global dispatcher invoked from Android: window.__shivoid_dispatch(resultJsonOrObject)
    window.__shivoid_dispatch = function(result) {
        try {
            const data = typeof result === "string" ? JSON.parse(result) : result;
            if (!data || !data.callbackId) return;

            const pending = pendingCallbacks.get(data.callbackId);
            if (pending) {
                pendingCallbacks.delete(data.callbackId);
                if (pending.timeoutId) clearTimeout(pending.timeoutId);

                if (data.success) {
                    const resData = data.data !== undefined ? data.data : data;
                    if (typeof resData === "object" && resData !== null) {
                        if (resData.statusCode !== undefined && resData.status === undefined) {
                            resData.status = resData.statusCode;
                        } else if (resData.status === undefined) {
                            resData.status = "success";
                        }
                        if (resData.success === undefined) resData.success = true;
                        if (resData.ok === undefined) {
                            resData.ok = resData.success !== false &&
                                (resData.statusCode === undefined || (resData.statusCode >= 200 && resData.statusCode < 300));
                        }
                    }
                    pending.resolve(resData);
                } else {
                    const rawError = data.error ||
                        (data.data && data.data.error) ||
                        (data.data && data.data.message) ||
                        "SHI.VOID native operation failed";
                    const err = new Error(rawError);
                    err.name = "ShivoidError";
                    err.success = false;
                    err.status = "error";
                    err.statusCode = (data.data && data.data.statusCode) || data.statusCode || 0;
                    err.errorType = (data.data && data.data.errorType) || data.errorType || "NETWORK_ERROR";
                    err.body = (data.data && data.data.body) || data.body || "";
                    err.operation = (data.data && data.data.operation) || data.operation || "";
                    err.endpoint = (data.data && data.data.endpoint) || data.endpoint || "";
                    err.data = data.data || data;
                    pending.reject(err);
                }
            }

            // Also emit custom window event if eventName is present
            if (data.eventName) {
                window.dispatchEvent(new CustomEvent("shivoid:" + data.eventName, { detail: data }));
            }
        } catch (e) {
            console.error("[SHI.VOID] Dispatch error:", e);
        }
    };

    const hasNative = () => typeof window.ShivoidNative !== "undefined";

    function registerCallback(resolve, reject, timeoutMs = 30000) {
        const cbId = nextCallbackId();
        const timeoutId = setTimeout(() => {
            if (pendingCallbacks.has(cbId)) {
                pendingCallbacks.delete(cbId);
                const timeoutErr = new Error("SHI.VOID native bridge request timed out after " + timeoutMs + "ms");
                timeoutErr.name = "ShivoidTimeoutError";
                timeoutErr.statusCode = 0;
                timeoutErr.errorType = "TIMEOUT";
                timeoutErr.status = "error";
                timeoutErr.success = false;
                reject(timeoutErr);
            }
        }, timeoutMs);

        pendingCallbacks.set(cbId, { resolve, reject, timeoutId });
        return cbId;
    }

    const Shivoid = {
        __initialized: true,
        version: "1.0",
        productName: "SHI.V01D",
        developer: "SHIVANSH THAKUR",
        isNativeAvailable: () => hasNative(),

        // Text-to-Speech
        tts: {
            speak: function(text, options) {
                options = options || {};
                const lang = options.lang || "en";
                const pitch = options.pitch !== undefined ? options.pitch : 1.0;
                const rate = options.rate !== undefined ? options.rate : 1.0;

                return new Promise((resolve, reject) => {
                    if (hasNative()) {
                        const cbId = registerCallback(resolve, reject, 60000);
                        const started = window.ShivoidNative.speak(text, lang, pitch, rate, cbId);
                        if (!started) {
                            pendingCallbacks.delete(cbId);
                            reject(new Error("Failed to start speech synthesis"));
                        }
                    } else if (window.speechSynthesis) {
                        // Desktop fallback
                        const utterance = new SpeechSynthesisUtterance(text);
                        utterance.lang = lang;
                        utterance.pitch = pitch;
                        utterance.rate = rate;
                        utterance.onend = () => resolve({ success: true, status: "success" });
                        utterance.onerror = (e) => reject(new Error("Speech synthesis error: " + e.error));
                        window.speechSynthesis.speak(utterance);
                    } else {
                        reject(new Error("TTS is not supported in this environment"));
                    }
                });
            },
            stop: function() {
                if (hasNative()) return window.ShivoidNative.stopTTS();
                if (window.speechSynthesis) {
                    window.speechSynthesis.cancel();
                    return true;
                }
                return false;
            },
            isSpeaking: function() {
                if (hasNative()) return window.ShivoidNative.isSpeaking();
                if (window.speechSynthesis) return window.speechSynthesis.speaking;
                return false;
            }
        },

        // Vibration / Haptics
        vibrate: function(durationOrPattern) {
            if (hasNative()) {
                if (Array.isArray(durationOrPattern)) {
                    return window.ShivoidNative.vibratePattern(JSON.stringify(durationOrPattern), -1);
                } else {
                    const duration = typeof durationOrPattern === "number" ? durationOrPattern : 100;
                    return window.ShivoidNative.vibrate(duration);
                }
            } else if (navigator.vibrate) {
                return navigator.vibrate(durationOrPattern);
            }
            return false;
        },
        cancelVibration: function() {
            if (hasNative()) return window.ShivoidNative.cancelVibration();
            if (navigator.vibrate) return navigator.vibrate(0);
            return false;
        },

        // Native Notifications
        notification: {
            show: function(title, message, id, channelId) {
                if (hasNative()) {
                    const notifId = typeof id === "number" ? id : Math.floor(Math.random() * 10000);
                    return window.ShivoidNative.showNotification(title || "SHI.V01D", message || "", notifId, channelId || "default");
                }
                if ("Notification" in window && Notification.permission === "granted") {
                    new Notification(title || "SHI.V01D", { body: message || "" });
                    return true;
                }
                return false;
            },
            cancel: function(id) {
                if (hasNative()) return window.ShivoidNative.cancelNotification(id);
                return false;
            }
        },

        // HTTP & Automate Communication (Native OkHttp engine)
        http: {
            get: function(url, headers) {
                return new Promise((resolve, reject) => {
                    if (hasNative()) {
                        const cbId = registerCallback(resolve, reject);
                        window.ShivoidNative.httpGet(url, JSON.stringify(headers || {}), cbId);
                    } else if (window.fetch) {
                        // Desktop browser fallback
                        window.fetch(url, { headers: headers || {} })
                            .then(async (res) => {
                                const body = await res.text();
                                const isSuccess = res.ok;
                                const respObj = {
                                    success: isSuccess,
                                    ok: isSuccess,
                                    status: res.status,
                                    statusCode: res.status,
                                    message: res.statusText,
                                    body: body,
                                    headers: {},
                                    operation: "get",
                                    endpoint: url
                                };
                                resolve(respObj);
                            })
                            .catch(reject);
                    } else {
                        reject(new Error("No HTTP client available"));
                    }
                });
            },

            post: function(url, body, contentType, headers) {
                return new Promise((resolve, reject) => {
                    let effectiveContentType = contentType;
                    let effectiveHeaders = headers;
                    if (typeof contentType === "object" && contentType !== null && headers === undefined) {
                        effectiveHeaders = contentType;
                        effectiveContentType = "application/json; charset=utf-8";
                    } else if (!effectiveContentType) {
                        effectiveContentType = typeof body === "object" ? "application/json; charset=utf-8" : "text/plain; charset=utf-8";
                    }

                    const bodyStr = typeof body === "object" ? JSON.stringify(body) : (body || "");

                    if (hasNative()) {
                        const cbId = registerCallback(resolve, reject);
                        window.ShivoidNative.httpPost(url, bodyStr, effectiveContentType, JSON.stringify(effectiveHeaders || {}), cbId);
                    } else if (window.fetch) {
                        // Desktop fallback
                        const reqHeaders = Object.assign({}, effectiveHeaders || {});
                        if (!reqHeaders["Content-Type"]) reqHeaders["Content-Type"] = effectiveContentType;
                        window.fetch(url, { method: "POST", body: bodyStr, headers: reqHeaders })
                            .then(async (res) => {
                                const respBody = await res.text();
                                const isSuccess = res.ok;
                                const respObj = {
                                    success: isSuccess,
                                    ok: isSuccess,
                                    status: res.status,
                                    statusCode: res.status,
                                    message: res.statusText,
                                    body: respBody,
                                    headers: {},
                                    operation: "post",
                                    endpoint: url
                                };
                                resolve(respObj);
                            })
                            .catch(reject);
                    } else {
                        reject(new Error("No HTTP client available"));
                    }
                });
            },

            // Dedicated Automate helper for triggering Automate (LlamaLab) HTTP endpoints
            automate: function(endpointUrl, payload) {
                return new Promise((resolve, reject) => {
                    let targetUrl = endpointUrl;
                    let targetPayload = payload;

                    // Allow calling automate(payload) where 1st arg is payload
                    if (typeof endpointUrl === "object" || !endpointUrl) {
                        targetPayload = endpointUrl || {};
                        targetUrl = Shivoid.automate.getEndpoint();
                    } else if (typeof endpointUrl === "string") {
                        const trimmed = endpointUrl.trim();
                        if (trimmed === "") {
                            targetUrl = Shivoid.automate.getEndpoint();
                        } else if (trimmed.startsWith("/")) {
                            const base = Shivoid.automate.getEndpoint().replace(/\/+$/, "");
                            targetUrl = base + trimmed;
                        } else {
                            targetUrl = trimmed;
                        }
                    }

                    let payloadStr = "{}";
                    if (typeof targetPayload === "object" && targetPayload !== null) {
                        try {
                            payloadStr = JSON.stringify(targetPayload);
                        } catch (e) {
                            payloadStr = "{}";
                        }
                    } else if (typeof targetPayload === "string") {
                        const trimmed = targetPayload.trim();
                        payloadStr = (trimmed === "" || trimmed === "[object Object]") ? "{}" : trimmed;
                    }

                    if (hasNative()) {
                        const cbId = registerCallback(resolve, reject);
                        window.ShivoidNative.callAutomate(targetUrl, payloadStr, cbId);
                    } else if (window.fetch) {
                        // Desktop fallback
                        window.fetch(targetUrl, {
                            method: "POST",
                            body: payloadStr,
                            headers: { "Content-Type": "application/json; charset=utf-8", "User-Agent": "SHI.VOID-Automate-Client/1.0" }
                        })
                        .then(async (res) => {
                            const respBody = await res.text();
                            const isSuccess = res.ok;
                            const respObj = {
                                success: isSuccess,
                                ok: isSuccess,
                                status: res.status,
                                statusCode: res.status,
                                message: res.statusText,
                                body: respBody,
                                operation: "automate",
                                endpoint: targetUrl
                            };
                            resolve(respObj);
                        })
                        .catch(reject);
                    } else {
                        reject(new Error("No HTTP client available"));
                    }
                });
            }
        },

        // Automate namespace - callable function and object
        automate: null, // Initialized below

        // Device & Battery Telemetry
        device: {
            getInfo: function() {
                if (!hasNative()) {
                    return {
                        appName: "SHI.V01D (Browser)",
                        appVersion: "1.0",
                        developer: "SHIVANSH THAKUR",
                        isOnline: navigator.onLine,
                        userAgent: navigator.userAgent
                    };
                }
                try {
                    return JSON.parse(window.ShivoidNative.getDeviceInfo());
                } catch (e) {
                    return {};
                }
            },
            getBattery: function() {
                if (!hasNative()) {
                    return { level: 100, isCharging: true, chargingType: "AC" };
                }
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
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(text || "");
                    return true;
                }
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
                    if (!hasNative()) return reject(new Error("Native camera bridge not available"));
                    const cbId = registerCallback(resolve, reject, 60000);
                    window.ShivoidNative.takePhoto(cbId);
                });
            }
        },

        // Files
        files: {
            pick: function(mimeType) {
                return new Promise((resolve, reject) => {
                    if (!hasNative()) return reject(new Error("Native file picker bridge not available"));
                    const cbId = registerCallback(resolve, reject, 60000);
                    window.ShivoidNative.pickFile(mimeType || "*/*", cbId);
                });
            }
        },

        // External navigation & System controls
        app: {
            openExternal: function(url) {
                if (hasNative()) return window.ShivoidNative.openExternal(url);
                window.open(url, "_blank");
                return true;
            },
            launch: function(packageName, fallbackUrl) {
                if (hasNative()) return window.ShivoidNative.launchApp(packageName, fallbackUrl || "");
                if (fallbackUrl) window.open(fallbackUrl, "_blank");
                return false;
            },
            share: function(text, title) {
                if (hasNative()) return window.ShivoidNative.shareText(text, title || "Share via SHI.V01D");
                if (navigator.share) {
                    navigator.share({ title: title || "SHI.V01D", text: text }).catch(() => {});
                    return true;
                }
                return false;
            },
            toast: function(message) {
                if (hasNative()) window.ShivoidNative.showToast(message);
                else console.log("[SHI.V01D Toast]:", message);
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

    // Configure Automate helper as both a callable function and an object with helper methods
    function automateHelper(endpointOrPayload, payload) {
        return Shivoid.http.automate(endpointOrPayload, payload);
    }
    automateHelper.getEndpoint = function() {
        if (hasNative() && window.ShivoidNative.getAutomateEndpoint) {
            return window.ShivoidNative.getAutomateEndpoint();
        }
        return "http://127.0.0.1:8080/";
    };
    automateHelper.setEndpoint = function(url) {
        if (hasNative() && window.ShivoidNative.setAutomateEndpoint) {
            return window.ShivoidNative.setAutomateEndpoint(url);
        }
        return false;
    };
    automateHelper.send = function(endpointOrPayload, payload) {
        return Shivoid.http.automate(endpointOrPayload, payload);
    };

    Shivoid.automate = automateHelper;
    Shivoid.callAutomate = automateHelper;
    Shivoid.sendAutomate = automateHelper;

    // Top-level shortcuts on window.Shivoid
    Shivoid.speak = (text, opts) => Shivoid.tts.speak(text, opts);
    Shivoid.stopTTS = () => Shivoid.tts.stop();
    Shivoid.isSpeaking = () => Shivoid.tts.isSpeaking();
    Shivoid.showNotification = (t, m, id, ch) => Shivoid.notification.show(t, m, id, ch);
    Shivoid.getBattery = () => Shivoid.device.getBattery();
    Shivoid.getDeviceInfo = () => Shivoid.device.getInfo();
    Shivoid.copyToClipboard = (txt) => Shivoid.clipboard.copy(txt);
    Shivoid.readClipboard = () => Shivoid.clipboard.read();
    Shivoid.takePhoto = () => Shivoid.camera.takePhoto();
    Shivoid.pickFile = (mime) => Shivoid.files.pick(mime);
    Shivoid.toast = (msg) => Shivoid.app.toast(msg);
    Shivoid.showToast = (msg) => Shivoid.app.toast(msg);
    Shivoid.openExternal = (url) => Shivoid.app.openExternal(url);
    Shivoid.launchApp = (pkg, fallback) => Shivoid.app.launch(pkg, fallback);
    Shivoid.shareText = (txt, title) => Shivoid.app.share(txt, title);
    Shivoid.httpGet = (url, headers) => Shivoid.http.get(url, headers);
    Shivoid.httpPost = (url, body, contentType, headers) => Shivoid.http.post(url, body, contentType, headers);

    window.Shivoid = Shivoid;
    window.SHIVOID = Shivoid; // Alias
    window.SHIV01D = Shivoid; // Brand alias for SHI.V01D
    console.log("[SHI.V01D] Native bridge initialized by SHIVANSH THAKUR. Access via window.Shivoid, window.SHIVOID, or window.SHIV01D.");

    // Trigger ready event
    window.dispatchEvent(new CustomEvent("shivoid:ready", { detail: { version: Shivoid.version, developer: Shivoid.developer } }));
})();
