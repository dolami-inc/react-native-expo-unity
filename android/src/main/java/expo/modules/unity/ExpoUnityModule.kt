package expo.modules.unity

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoUnityModule : Module() {

    override fun definition() = ModuleDefinition {
        Name("ExpoUnity")

        Events("onUnityMessage")

        Function("postMessage") { gameObject: String, methodName: String, message: String ->
            UnityBridge.getInstance().sendMessage(gameObject, methodName, message)
        }

        Function("pauseUnity") { pause: Boolean ->
            UnityBridge.getInstance().setPaused(pause)
        }

        Function("unloadUnity") {
            UnityBridge.getInstance().unload()
        }

        Function("isInitialized") {
            UnityBridge.getInstance().isInitialized
        }

        View(ExpoUnityView::class) {
            Events("onUnityMessage")

            Prop("autoUnloadOnUnmount") { view: ExpoUnityView, value: Boolean ->
                view.autoUnloadOnUnmount = value
            }
        }

        OnActivityEntersBackground {
            val bridge = UnityBridge.getInstance()
            if (bridge.isInitialized) {
                bridge.wasRunningBeforeBackground = true
                bridge.setPaused(true)
            } else {
                bridge.wasRunningBeforeBackground = false
            }
        }

        OnActivityEntersForeground {
            val bridge = UnityBridge.getInstance()
            if (bridge.wasRunningBeforeBackground) {
                bridge.setPaused(false)
                bridge.wasRunningBeforeBackground = false
            }
        }

        OnActivityDestroys {
            val bridge = UnityBridge.getInstance()
            if (bridge.isInitialized) {
                bridge.unload()
            }
        }
    }
}
