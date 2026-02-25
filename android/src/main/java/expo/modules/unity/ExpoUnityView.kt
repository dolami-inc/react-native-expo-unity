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

        if (bridge.isReady) {
            // Unity already initialized — just reparent the view into this container
            bridge.addUnityViewToGroup(this)
            Log.i(TAG, "Unity already ready, reparented view")
        } else {
            // Initialize Unity with a callback that reparents when ready
            bridge.initialize(activity) {
                post {
                    bridge.addUnityViewToGroup(this)
                    Log.i(TAG, "Unity ready, view reparented into container")
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        val bridge = UnityBridge.getInstance()
        if (!bridge.isReady) return
        bridge.unityPlayer?.windowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        val bridge = UnityBridge.getInstance()
        bridge.onMessage = null

        if (bridge.isInitialized) {
            if (autoUnloadOnUnmount) {
                bridge.unload()
                Log.i(TAG, "Auto-unloaded (view detached)")
            } else {
                // Park Unity in the background instead of unloading
                bridge.parkUnityViewInBackground()
                bridge.setPaused(true)
                Log.i(TAG, "Parked in background (autoUnloadOnUnmount=false)")
            }
        }

        super.onDetachedFromWindow()
    }
}
