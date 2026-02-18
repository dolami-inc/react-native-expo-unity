import ExpoModulesCore
import UIKit

// Handles app lifecycle events for Unity
// Auto pause/resume when app goes to background/foreground
public class ExpoUnityAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  // Track if Unity was running before going to background
  // so we don't resume if it was already paused by the developer
  private static var wasRunningBeforeBackground = false

  public func applicationWillResignActive(_ application: UIApplication) {
    let bridge = UnityBridge.shared()
    if bridge.isInitialized() {
      ExpoUnityAppDelegateSubscriber.wasRunningBeforeBackground = true
      bridge.pause(true)
      NSLog("[ExpoUnity] Auto-paused (app entering background)")
    } else {
      ExpoUnityAppDelegateSubscriber.wasRunningBeforeBackground = false
    }
  }

  public func applicationDidBecomeActive(_ application: UIApplication) {
    if ExpoUnityAppDelegateSubscriber.wasRunningBeforeBackground {
      UnityBridge.shared().pause(false)
      ExpoUnityAppDelegateSubscriber.wasRunningBeforeBackground = false
      NSLog("[ExpoUnity] Auto-resumed (app entering foreground)")
    }
  }

  public func applicationWillTerminate(_ application: UIApplication) {
    if UnityBridge.shared().isInitialized() {
      UnityBridge.shared().unload()
      NSLog("[ExpoUnity] Auto-unloaded (app terminating)")
    }
  }
}
