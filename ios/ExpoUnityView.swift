import ExpoModulesCore
import UIKit

class ExpoUnityView: ExpoView {
  private let onUnityMessage = EventDispatcher()
  var autoUnloadOnUnmount: Bool = true

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)

    // Initialize Unity when the view is created
    DispatchQueue.main.async { [weak self] in
      self?.setupUnity()
    }
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  private func setupUnity() {
    let bridge = UnityBridge.shared()

    // Initialize if needed
    if !bridge.isInitialized() {
      bridge.initialize()
    }

    // Set message callback
    bridge.onMessage = { [weak self] message in
      DispatchQueue.main.async {
        self?.onUnityMessage([
          "message": message
        ])
      }
    }

    // Mount Unity's root view
    mountUnityView()
  }

  private func mountUnityView() {
    guard let unityView = UnityBridge.shared().unityRootView() else {
      NSLog("[ExpoUnity] Unity root view not available yet")
      return
    }

    // Hide Unity's own window if it exists
    if let unityWindow = UnityBridge.shared().unityWindow() {
      if let myWindow = self.window, unityWindow != myWindow {
        unityWindow.isHidden = true
        unityWindow.isUserInteractionEnabled = false
        myWindow.makeKeyAndVisible()
      }
    }

    unityView.frame = self.bounds
    if unityView.superview != self {
      self.addSubview(unityView)
    }
  }

  override func layoutSubviews() {
    super.layoutSubviews()

    // Keep Unity view in sync with our bounds
    if let unityView = UnityBridge.shared().unityRootView(),
       unityView.superview == self {
      unityView.frame = self.bounds
    } else {
      // Try mounting again if not yet mounted
      mountUnityView()
    }
  }

  // Cleanup when view is removed
  override func removeFromSuperview() {
    let bridge = UnityBridge.shared()
    bridge.onMessage = nil

    if autoUnloadOnUnmount && bridge.isInitialized() {
      bridge.unload()
      NSLog("[ExpoUnity] Auto-unloaded (view removed from superview)")
    } else if !autoUnloadOnUnmount && bridge.isInitialized() {
      bridge.pause(true)
      NSLog("[ExpoUnity] Auto-paused on unmount (autoUnloadOnUnmount=false)")
    }

    super.removeFromSuperview()
  }
}
