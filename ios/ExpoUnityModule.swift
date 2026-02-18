import ExpoModulesCore

public class ExpoUnityModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoUnity")

    // Send a message from RN to Unity
    Function("postMessage") { (gameObject: String, methodName: String, message: String) in
      UnityBridge.shared().sendMessage(gameObject, methodName: methodName, message: message)
    }

    // Pause / resume Unity
    Function("pauseUnity") { (pause: Bool) in
      UnityBridge.shared().pause(pause)
    }

    // Unload Unity (free memory)
    Function("unloadUnity") {
      UnityBridge.shared().unload()
    }

    // Check if Unity is initialized
    Function("isInitialized") { () -> Bool in
      return UnityBridge.shared().isInitialized()
    }

    // Event sent from Unity → RN
    Events("onUnityMessage")

    // The native Unity view
    View(ExpoUnityView.self) {
      Events("onUnityMessage")

      Prop("autoUnloadOnUnmount") { (view: ExpoUnityView, value: Bool) in
        view.autoUnloadOnUnmount = value
      }
    }
  }
}
