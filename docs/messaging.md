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

    /// Send a structured event to React Native
    public static void SendEvent(string eventName, object data = null)
    {
        var msg = new EventMessage { @event = eventName, data = data };
        string json = JsonUtility.ToJson(msg);

        #if UNITY_IOS && !UNITY_EDITOR
        sendMessageToMobileApp(json);
        #endif
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

## React Native Implementation

```tsx
import { UnityView } from "expo-unity";

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

## Why JSON?

- Consistent structure for all messages
- Easy to parse on both sides
- Type-safe with interfaces/classes
- Extensible — add fields without breaking existing handlers
- Debuggable — readable in logs
