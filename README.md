# @dolami-inc/react-native-expo-unity

Unity as a Library (UaaL) bridge for React Native / Expo.

## Install

```bash
npm install @dolami-inc/react-native-expo-unity
# or
yarn add @dolami-inc/react-native-expo-unity
# or
bun add @dolami-inc/react-native-expo-unity
```

## Quick Start

```tsx
import { UnityView, type UnityViewRef } from "@dolami-inc/react-native-expo-unity";

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
import { postMessage, pauseUnity, resumeUnity, unloadUnity, isInitialized } from "@dolami-inc/react-native-expo-unity";
```

## Setup

### 1. Unity project — add plugins

Copy the platform bridge files into your Unity project:

**iOS:**

```bash
# From node_modules after install
cp node_modules/@dolami-inc/react-native-expo-unity/plugin/NativeCallProxy.h  <UnityProject>/Assets/Plugins/iOS/
cp node_modules/@dolami-inc/react-native-expo-unity/plugin/NativeCallProxy.mm <UnityProject>/Assets/Plugins/iOS/
```

**Android:**

No plugin files need to be copied — the `NativeCallProxy` Java class ships with the module and is available at runtime automatically. Your Unity C# code calls it via `AndroidJavaClass` (see [Messaging Guide](docs/messaging.md)).

### 2. Unity project — build for your target platform

#### iOS

1. Unity → File → Build Settings → iOS → Build
2. Open generated Xcode project
3. Select `NativeCallProxy.h` in Libraries/Plugins/iOS/
4. Set Target Membership → `UnityFramework` → **Public**
5. **Select the `Data` folder** in the Project Navigator
6. In the right panel under **Target Membership**, check **`UnityFramework`**
   > **This is critical.** Without this, the `Data` folder (which contains `global-metadata.dat` and all Unity assets) will NOT be included inside `UnityFramework.framework`. The app will crash at launch with: `Could not open .../global-metadata.dat — IL2CPP initialization failed`
7. Build `UnityFramework` scheme

#### Android

1. Unity → File → Build Settings → Android
2. Check **Export Project** (do not "Build" directly — you need the Gradle project)
3. Set Scripting Backend to **IL2CPP**
4. Set Target Architectures: **ARMv7** and **ARM64**
5. Click **Export** and save to a directory (e.g. `unity/builds/android`)

### 3. Copy build artifacts to your RN project

#### iOS

Create `unity/builds/ios/` in your project root and copy the built framework and static libraries:

```bash
mkdir -p unity/builds/ios

# Copy the compiled framework (should already contain Data/ inside after step 2.6)
cp -R <xcode-build-output>/UnityFramework.framework unity/builds/ios/

# Copy static libraries from the Unity Xcode project root
cp <unity-xcode-project>/*.a unity/builds/ios/
```

Verify that `Data/` exists inside the framework:

```bash
ls unity/builds/ios/UnityFramework.framework/Data
# Should show: Managed/  Resources/  etc.
```

The podspec references these files **directly by path** — nothing is copied or embedded into the npm package. Updating your Unity build is as simple as replacing the contents of `unity/builds/ios/` and re-running `pod install`.

> Custom path? Set `EXPO_UNITY_PATH` environment variable pointing to your Unity build directory, or pass `unityPath` to the config plugin (see step 4).

#### Android

The Unity export directory (containing the `unityLibrary` folder) should be at `unity/builds/android/` in your project root. The config plugin will automatically include the `:unityLibrary` Gradle module.

```bash
# Verify the structure
ls unity/builds/android/unityLibrary
# Should show: libs/  src/  build.gradle  etc.
```

> Custom path? Set `EXPO_UNITY_ANDROID_PATH` environment variable, or pass `androidUnityPath` to the config plugin.

### 4. Add the config plugin to `app.json`

```json
{
  "expo": {
    "plugins": [
      "@dolami-inc/react-native-expo-unity"
    ]
  }
}
```

The plugin automatically configures:

**iOS:**
- `ENABLE_BITCODE = NO` — Unity does not support bitcode
- `CLANG_CXX_LANGUAGE_STANDARD = c++17` — required for Unity headers
- Embeds `UnityFramework.framework` via a build script phase

**Android:**
- Includes `:unityLibrary` module in `settings.gradle`
- Adds `flatDir` repository for Unity's native libs
- Adds `ndk.abiFilters` for `armeabi-v7a` and `arm64-v8a`

If your Unity artifacts are in custom paths:

```json
["@dolami-inc/react-native-expo-unity", {
  "unityPath": "/absolute/path/to/unity/builds/ios",
  "androidUnityPath": "/absolute/path/to/unity/builds/android"
}]
```

### 5. Build

```bash
# iOS
expo prebuild --platform ios --clean
expo run:ios --device

# Android
expo prebuild --platform android --clean
expo run:android
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
// iOS — uses extern "C" DllImport
#if UNITY_IOS && !UNITY_EDITOR
[DllImport("__Internal")]
private static extern void sendMessageToMobileApp(string message);
#endif

// Android — uses AndroidJavaClass
private static void SendToMobile(string message) {
#if UNITY_IOS && !UNITY_EDITOR
    sendMessageToMobileApp(message);
#elif UNITY_ANDROID && !UNITY_EDITOR
    using (var proxy = new AndroidJavaClass("com.expounity.bridge.NativeCallProxy")) {
        proxy.CallStatic("sendMessageToMobileApp", message);
    }
#endif
}

// Usage:
SendToMobile("{\"event\":\"image_taken\",\"data\":{\"path\":\"/tmp/photo.jpg\"}}");
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
- **Physical device** — iOS: Unity renders only on device, Simulator shows a placeholder. Android: physical device or emulator with ARM support.
- **Unity build artifacts** — must be exported/copied manually into your project (not bundled via npm)

## Platform Support

| Platform | Status |
|---|---|
| iOS Device | Supported |
| iOS Simulator | Not supported — renders a placeholder view |
| Android Device | Supported |
| Android Emulator | Supported (ARM-based emulators only) |

## Limitations

- **Single instance** — only one Unity view at a time, cannot run multiple
- **Full-screen rendering only** — Unity renders full-screen within its view (Unity limitation)
- **Memory retention** — after `unloadUnity()`, Unity retains 80-180MB in memory (Unity limitation)
- **No reload after quit** — if Unity calls `Application.Quit()` on iOS, it cannot be restarted without restarting the app
- **No hot reload** — native code changes require a full rebuild

## License

MIT
