# react-native-expo-unity

Unity as a Library (UaaL) bridge for React Native / Expo.

> ⚠️ **iOS only** — Android support is coming soon.

## Install

```bash
npm install react-native-expo-unity
# or
yarn add react-native-expo-unity
# or
bun add react-native-expo-unity
```

## Quick Start

```tsx
import { UnityView, type UnityViewRef } from "react-native-expo-unity";

const unityRef = useRef<UnityViewRef>(null);

<UnityView
  ref={unityRef}
  style={{ flex: 1 }}
  onUnityMessage={(e) => console.log(e.message)}
/>

// Send message to Unity
unityRef.current?.postMessage("GameObject", "Method", "payload");
```

## API

### `<UnityView />`

| Prop | Type | Default | Description |
|---|---|---|---|
| `onUnityMessage` | `(e: { message: string }) => void` | — | Message from Unity |
| `autoUnloadOnUnmount` | `boolean` | `true` | Unload Unity when view unmounts. Set `false` to pause only (keeps state). |
| `style` | `ViewStyle` | — | Must have dimensions (e.g. `flex: 1`) |
| `ref` | `UnityViewRef` | — | Imperative methods |

### Ref Methods

```tsx
unityRef.current?.postMessage(gameObject, methodName, message)
unityRef.current?.pauseUnity()
unityRef.current?.resumeUnity()
unityRef.current?.unloadUnity()
```

### Standalone Functions

Same as ref methods, callable anywhere (operates on the singleton):

```tsx
import { postMessage, pauseUnity, resumeUnity, unloadUnity, isInitialized } from "react-native-expo-unity";
```

## Setup

### 1. Unity project — add plugin

Copy the plugin files into your Unity project:

```bash
# From node_modules after install
cp node_modules/react-native-expo-unity/plugin/NativeCallProxy.h  <UnityProject>/Assets/Plugins/iOS/
cp node_modules/react-native-expo-unity/plugin/NativeCallProxy.mm <UnityProject>/Assets/Plugins/iOS/
```

### 2. Unity project — build iOS

1. Unity → File → Build Settings → iOS → Build
2. Open generated Xcode project
3. Select `NativeCallProxy.h` in Libraries/Plugins/iOS/
4. Set Target Membership → `UnityFramework` → **Public**
5. Build `UnityFramework` scheme

### 3. Copy build artifacts to your RN project

Create `unity/builds/ios/` in your project root and copy:

```bash
mkdir -p unity/builds/ios
cp -R <unity-build>/UnityFramework.framework unity/builds/ios/
cp <unity-build>/{baselib.a,il2cpp.a,libGameAssembly.a} unity/builds/ios/
```

> Custom path? Set `EXPO_UNITY_PATH` environment variable to your Unity build directory.

### 4. Add the config plugin to `app.json`

```json
{
  "expo": {
    "plugins": [
      "react-native-expo-unity"
    ]
  }
}
```

The plugin automatically configures the required Xcode build settings:
- `ENABLE_BITCODE = NO` — Unity does not support bitcode
- `CLANG_CXX_LANGUAGE_STANDARD = c++17` — required for Unity headers
- `FRAMEWORK_SEARCH_PATHS` — adds the Unity build artifacts directory

If your Unity artifacts are in a custom path, pass the option:

```json
["react-native-expo-unity", { "unityPath": "/absolute/path/to/unity/builds/ios" }]
```

### 5. Build

```bash
expo prebuild --platform ios --clean
expo run:ios --device
```

## Lifecycle

Unity is a **singleton** — one instance for the entire app.

| State | Memory | Re-entry |
|---|---|---|
| Running | ~200-500MB+ (depends on scene/assets) | Already running |
| Paused | Same (frozen in memory, no CPU/GPU usage) | `resumeUnity()` — instant, state preserved |
| Unloaded | ~80-180MB retained (Unity limitation) | Remount `<UnityView />` — ~1-2s reinit, state reset |

### Auto behavior

| Event | What happens |
|---|---|
| `<UnityView />` mounts | Unity initializes and starts rendering |
| `<UnityView />` unmounts | Unity unloads (or pauses if `autoUnloadOnUnmount={false}`) |
| App → background | Unity pauses |
| App → foreground | Unity resumes |

### Manual control

Screen focus/blur is **not** automatic — handle with `useFocusEffect`:

```tsx
useFocusEffect(
  useCallback(() => {
    unityRef.current?.resumeUnity();
    return () => unityRef.current?.pauseUnity();
  }, [])
);
```

## Messaging

### RN → Unity

```tsx
unityRef.current?.postMessage("GameManager", "LoadAvatar", '{"id":"avatar_01"}');
```

```csharp
// Unity C# — on "GameManager" GameObject
public void LoadAvatar(string json) { /* ... */ }
```

### Unity → RN

```csharp
#if UNITY_IOS && !UNITY_EDITOR
[DllImport("__Internal")]
private static extern void sendMessageToMobileApp(string message);
#endif

// Recommended: JSON format
sendMessageToMobileApp("{\"event\":\"image_taken\",\"data\":{\"path\":\"/tmp/photo.jpg\"}}");
```

```tsx
<UnityView onUnityMessage={(e) => {
  const msg = JSON.parse(e.message);
  // msg.event, msg.data
}} />
```

> See [Messaging Guide](docs/messaging.md) for recommended patterns.

## Docs

- [Lifecycle Deep Dive](docs/lifecycle.md) — navigation scenarios, state management, trade-offs
- [Messaging Guide](docs/messaging.md) — recommended JSON format, Unity C# + RN examples

## Requirements

- **Expo SDK 54+**
- **React Native New Architecture** (Fabric) — old architecture not supported
- **Physical iOS device** — Unity renders only on device; Simulator shows a placeholder view
- **Unity build artifacts** — must be copied manually into your project (~2GB, not bundled via npm)

## Platform Support

| Platform | Status |
|---|---|
| iOS Device | ✅ Supported |
| iOS Simulator | ⚠️ Not supported — renders a placeholder view |
| Android | 🚧 Coming soon |

## Limitations

- **Single instance** — only one Unity view at a time, cannot run multiple
- **Full-screen rendering only** — Unity renders full-screen within its view (Unity limitation)
- **Memory retention** — after `unloadUnity()`, Unity retains 80-180MB in memory (Unity limitation)
- **No reload after quit** — if Unity calls `Application.Quit()` on iOS, it cannot be restarted without restarting the app
- **No hot reload** — native code changes require a full rebuild

## License

MIT
