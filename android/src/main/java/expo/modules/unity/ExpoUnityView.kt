package expo.modules.unity

import android.content.Context
import android.util.Log
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

class ExpoUnityView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    companion object {
        private const val TAG = "ExpoUnity"
    }

    val onUnityMessage by EventDispatcher()
    var autoUnloadOnUnmount: Boolean = true

    init {
        post { setupUnity() }
    }

    private fun setupUnity() {
        val activity = appContext.currentActivity ?: run {
            Log.w(TAG, "No activity available for Unity initialization")
            return
        }

        val bridge = UnityBridge.getInstance()

        bridge.onMessage = { message ->
            post {
                onUnityMessage(mapOf("message" to message))
            }
        }

        if (bridge.isInitialized) {
            // Unity already created — attach the view to this container
            bridge.attachToContainer(this)
        } else {
            // Create Unity player, then attach the view once ready
            bridge.initialize(activity) {
                bridge.attachToContainer(this)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        val bridge = UnityBridge.getInstance()
        if (!bridge.isInitialized) return
        bridge.unityPlayer?.windowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        val bridge = UnityBridge.getInstance()
        bridge.onMessage = null

        if (bridge.isInitialized) {
            if (autoUnloadOnUnmount) {
                bridge.detachFromContainer()
                bridge.unload()
                Log.i(TAG, "Detached and unloaded (view detached)")
            } else {
                bridge.setPaused(true)
                Log.i(TAG, "Paused (autoUnloadOnUnmount=false)")
            }
        }

        super.onDetachedFromWindow()
    }
}
