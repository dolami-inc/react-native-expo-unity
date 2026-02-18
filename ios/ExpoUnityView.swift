import ExpoModulesCore
import UIKit

class ExpoUnityView: ExpoView {
  private let onUnityMessage = EventDispatcher()
  var autoUnloadOnUnmount: Bool = true

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)

    #if targetEnvironment(simulator)
    setupSimulatorPlaceholder()
    #else
    DispatchQueue.main.async { [weak self] in
      self?.setupUnity()
    }
    #endif
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  // MARK: - Simulator

  #if targetEnvironment(simulator)
  private func setupSimulatorPlaceholder() {
    backgroundColor = .black

    let label = UILabel()
    label.text = "Unity is not available\non iOS Simulator"
    label.textAlignment = .center
    label.numberOfLines = 0
    label.textColor = UIColor(white: 0.6, alpha: 1)
    label.font = .systemFont(ofSize: 14)
    label.translatesAutoresizingMaskIntoConstraints = false
    addSubview(label)

    NSLayoutConstraint.activate([
      label.centerXAnchor.constraint(equalTo: centerXAnchor),
      label.centerYAnchor.constraint(equalTo: centerYAnchor),
      label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 16),
      label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16),
    ])
  }
  #endif

  // MARK: - Device

  #if !targetEnvironment(simulator)
  private func setupUnity() {
    let bridge = UnityBridge.shared()

    if !bridge.isInitialized() {
      bridge.initialize()
    }

    bridge.onMessage = { [weak self] message in
      DispatchQueue.main.async {
        self?.onUnityMessage([
          "message": message
        ])
      }
    }

    mountUnityView()
  }

  private func mountUnityView() {
    guard let unityView = UnityBridge.shared().unityRootView() else {
      NSLog("[ExpoUnity] Unity root view not available yet")
      return
    }

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

    if let unityView = UnityBridge.shared().unityRootView(),
       unityView.superview == self {
      unityView.frame = self.bounds
    } else {
      mountUnityView()
    }
  }

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
  #endif
}
