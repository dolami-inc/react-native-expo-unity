import { requireNativeView } from "expo";
import { forwardRef, useImperativeHandle, useCallback, useRef } from "react";
import type { ViewProps } from "react-native";
import type { UnityMessageEvent } from "./types";
import { postMessage, pauseUnity, resumeUnity, unloadUnity } from "./ExpoUnity";

// Native view from Expo Modules
const NativeUnityView = requireNativeView("ExpoUnity");

export interface UnityViewProps extends ViewProps {
  onUnityMessage?: (event: UnityMessageEvent) => void;
  /**
   * If true (default), Unity will automatically unload when the view unmounts.
   * If false, Unity will only pause on unmount — state is preserved for faster re-mount.
   * Use false when users frequently navigate back and forth to the Unity screen.
   */
  autoUnloadOnUnmount?: boolean;
}

export interface UnityViewRef {
  postMessage: (gameObject: string, methodName: string, message: string) => void;
  pauseUnity: () => void;
  resumeUnity: () => void;
  unloadUnity: () => void;
}

export const UnityView = forwardRef<UnityViewRef, UnityViewProps>(
  ({ onUnityMessage, autoUnloadOnUnmount = true, ...props }, ref) => {
    const nativeRef = useRef(null);

    useImperativeHandle(ref, () => ({
      postMessage,
      pauseUnity,
      resumeUnity,
      unloadUnity,
    }));

    const handleUnityMessage = useCallback(
      (event: { nativeEvent: UnityMessageEvent }) => {
        onUnityMessage?.(event.nativeEvent);
      },
      [onUnityMessage]
    );

    return (
      <NativeUnityView
        ref={nativeRef}
        onUnityMessage={handleUnityMessage}
        autoUnloadOnUnmount={autoUnloadOnUnmount}
        {...props}
      />
    );
  }
);

UnityView.displayName = "UnityView";
