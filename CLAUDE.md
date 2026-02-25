# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

react-native-expo-unity is an Expo module that embeds Unity as a Library (UaaL) into React Native / Expo apps. It provides a React component (`<UnityView />`) and imperative APIs for bidirectional messaging and lifecycle control between React Native and Unity. Supports both iOS (physical devices only) and Android.

## Build & Development Commands

```bash
# Install dependencies (project uses bun)
bun install

# Type-check
npx tsc --noEmit

# Build TypeScript to ./build
npx tsc
```

There are no test scripts, linters, or formatters configured in this project.

## Architecture

### Dual-Layer Design

**TypeScript Layer** (`src/`):
- `index.ts` — Public API exports
- `ExpoUnity.ts` — Native module bridge wrapping `ExpoUnityModule` (postMessage, pause, resume, unload, isInitialized)
- `UnityView.tsx` — React component using `requireNativeView` from Expo with imperative ref handle
- `types.ts` — Shared type definitions

**iOS Native Layer** (`ios/`):
- `ExpoUnityModule.swift` — Expo Modules Core integration exposing functions and events to JS
- `ExpoUnityView.swift` — Native view extending `ExpoView`, mounts Unity's root view as subview
- `ExpoUnityAppDelegateSubscriber.swift` — App lifecycle handling (auto-pause on background, auto-resume on foreground)
- `UnityBridge.h/.mm` — Objective-C++ singleton managing UnityFramework lifecycle (init, message routing, pause/unload)
- `ExpoUnity.podspec` — CocoaPods spec linking Unity build artifacts and static libraries

**Android Native Layer** (`android/`):
- `ExpoUnityModule.kt` — Expo Modules Kotlin integration exposing functions, events, view, and lifecycle hooks to JS
- `ExpoUnityView.kt` — Native view extending `ExpoView`, mounts UnityPlayer as child FrameLayout
- `UnityBridge.kt` — Kotlin singleton managing UnityPlayer lifecycle (init, message routing, pause/unload), implements `IUnityPlayerLifecycleEvents`
- `NativeCallProxy.java` (`com.expounity.bridge`) — Static Java bridge for Unity→RN messaging, called from Unity C# via `AndroidJavaClass`
- `build.gradle` — Gradle build script resolving `unity-classes.jar` as `compileOnly` dependency

**Plugin Files** (`plugin/`):
- `NativeCallProxy.h/.mm` — iOS bidirectional communication protocol files that users copy into their Unity project's `Assets/Plugins/iOS/`

### Key Patterns

- **Singleton**: `UnityBridge` is a global singleton on both platforms — only one Unity instance per app
- **Event Flow (iOS)**: RN→Unity via `sendMessageToGOWithName:functionName:message:` on UnityFramework; Unity→RN via extern "C" `sendMessageToMobileApp` → `NativeCallsProtocol` → EventDispatcher → `onUnityMessage` prop
- **Event Flow (Android)**: RN→Unity via `UnityPlayer.UnitySendMessage()`; Unity→RN via `AndroidJavaClass("com.expounity.bridge.NativeCallProxy")` → `MessageListener` → EventDispatcher → `onUnityMessage` prop
- **Lifecycle States**: NotInitialized → Running ↔ Paused → Unloaded. View mount triggers init, `autoUnloadOnUnmount` (default true) controls cleanup behavior
- **Unity artifacts (iOS)**: UnityFramework.framework + static libs (.a files) are manually copied by users; path configurable via `EXPO_UNITY_PATH` env var (defaults to `unity/builds/ios/`)
- **Unity artifacts (Android)**: Unity "Export Project" output (containing `unityLibrary/`) placed at path configurable via `EXPO_UNITY_ANDROID_PATH` env var (defaults to `unity/builds/android/`)

### Platform Requirements

- Expo >= 54.0, New Architecture (Fabric) required
- iOS physical device only (no simulator support), iOS 15.1 minimum, C++17, bitcode disabled
- Android minSdk 24, physical device or ARM-based emulator
