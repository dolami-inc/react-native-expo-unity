# Messaging Guide

The bridge passes raw strings between Unity and React Native. You can use any format, but we recommend **JSON** for consistency.

## Recommended Format

All messages as JSON with `event` + `data`:

```json
{
  "event": "event_name",
  "data": { ... }
}
```

### Unity → RN Examples

```json
{ "event": "unity_ready", "data": {} }
{ "event": "image_taken", "data": { "path": "/tmp/photo.jpg", "w": 828, "h": 1792 } }
{ "event": "session_close", "data": { "reason": "user_request" } }
{ "event": "avatar_loaded", "data": { "id": "avatar_01", "name": "Cat" } }
{ "event": "error", "data": { "code": "CAMERA_DENIED", "message": "Camera permission denied" } }
```

### RN → Unity Examples

```json
{ "event": "load_avatar", "data": { "id": "avatar_01" } }
{ "event": "set_config", "data": { "quality": "high", "ar_enabled": true } }
{ "event": "take_photo", "data": {} }
```

## Unity C# Implementation

```csharp
using System.Runtime.InteropServices;
using UnityEngine;

public static class RNBridge
{
    #if UNITY_IOS && !UNITY_EDITOR
    [DllImport("__Internal")]
    private static extern void sendMessageToMobileApp(string message);
    #endif

    /// Send a raw string message to React Native
    public static void Send(string message)
    {
        #if UNITY_IOS && !UNITY_EDITOR
        sendMessageToMobileApp(message);
        #elif UNITY_ANDROID && !UNITY_EDITOR
        using (var proxy = new AndroidJavaClass("com.expounity.bridge.NativeCallProxy"))
        {
            proxy.CallStatic("sendMessageToMobileApp", message);
        }
        #endif
    }

    /// Send a structured event to React Native
    public static void SendEvent(string eventName, object data = null)
    {
        var msg = new EventMessage { @event = eventName, data = data };
        string json = JsonUtility.ToJson(msg);
        Send(json);
    }

    [System.Serializable]
    private class EventMessage
    {
        public string @event;
        public object data;
    }
}

// Usage:
// RNBridge.SendEvent("unity_ready");
// RNBridge.SendEvent("image_taken", new { path = "/tmp/photo.jpg", w = 828, h = 1792 });
```

### Platform Details

**iOS:** The `sendMessageToMobileApp` function is defined as an `extern "C"` symbol in the `NativeCallProxy.mm` file that you copy into your Unity project's `Assets/Plugins/iOS/`. At runtime, UnityBridge registers itself as the `NativeCallsProtocol` handler and receives the message.

**Android:** The `NativeCallProxy` Java class ships with the module at `com.expounity.bridge.NativeCallProxy`. Unity C# code calls it via `AndroidJavaClass` — no additional plugin files need to be copied. At runtime, `UnityBridge` registers itself as a `MessageListener` and receives the message.

## React Native Implementation

```tsx
import { UnityView } from "@dolami-inc/react-native-expo-unity";

interface UnityEvent<T = unknown> {
  event: string;
  data: T;
}

function parseUnityMessage<T = unknown>(raw: string): UnityEvent<T> | null {
  try {
    return JSON.parse(raw);
  } catch {
    console.warn("[Unity] Invalid message:", raw);
    return null;
  }
}

// Usage:
<UnityView
  onUnityMessage={(e) => {
    const msg = parseUnityMessage(e.message);
    if (!msg) return;

    switch (msg.event) {
      case "unity_ready":
        console.log("Unity is ready");
        break;
      case "image_taken":
        handleImageTaken(msg.data as { path: string; w: number; h: number });
        break;
      case "error":
        handleError(msg.data as { code: string; message: string });
        break;
    }
  }}
/>
```

## Delivery Guarantees (Unity → RN)

Unity starts emitting before React Native can listen. On iOS the first scene
loads *inside* `runEmbeddedWithArgc:`, so a message sent from `Awake()`/`Start()`
can be produced before `<UnityView />` has attached its handler; on Android the
Unity thread starts with the player, ahead of the view.

The native bridge closes that window for you:

- Messages emitted while no view is attached are **buffered** (up to 128, oldest
  dropped first) and **replayed in arrival order** the moment a view attaches.
  This covers both the first attach and re-attaches — `react-native-screens`
  detaches the native view on tab switches without remounting the component.
- A message can therefore arrive **later than it was sent**, and a one-shot event
  can be **delivered twice** across an unmount/remount cycle. Make handlers
  idempotent: guard state transitions (`if (isReady) return;`) instead of
  assuming a readiness event fires exactly once.
- The backlog is **dropped on `unloadUnity()`** (and on Unity quitting). Those
  messages describe a process that no longer exists, so replaying them into the
  next one would promote readiness before the new engine has booted.

You do not need to poll Unity for readiness. If a readiness event never arrives,
that is a bug in this module — file an issue rather than adding a probe.

## Why JSON?

- Consistent structure for all messages
- Easy to parse on both sides
- Type-safe with interfaces/classes
- Extensible — add fields without breaking existing handlers
- Debuggable — readable in logs
