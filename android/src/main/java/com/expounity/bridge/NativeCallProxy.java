package com.expounity.bridge;

/**
 * Bridge for Unity -> React Native messaging on Android.
 *
 * Unity C# code calls this via AndroidJavaClass:
 *   var proxy = new AndroidJavaClass("com.expounity.bridge.NativeCallProxy");
 *   proxy.CallStatic("sendMessageToMobileApp", message);
 *
 * The module registers a MessageListener during initialization to receive messages.
 */
public class NativeCallProxy {

    public interface MessageListener {
        void onMessage(String message);
    }

    private static MessageListener listener;

    public static void registerListener(MessageListener newListener) {
        listener = newListener;
    }

    public static void sendMessageToMobileApp(String message) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }
}
