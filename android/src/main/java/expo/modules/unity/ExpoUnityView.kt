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

    // Let Android lay out this view's subtree instead of React Native. Unity's
    // SurfaceView resizes its render surface by calling requestLayout(), but RN
    // swallows that call — so the surface stays at a stale size and doesn't fill
    // the view, leaving a background gap (and, before this, ignoring the RN
    // bounds entirely). With this enabled, requestLayout() triggers a real
    // Android measure/layout pass so the Unity surface matches the bounds
    // React Native assigns from the JS style (the equivalent of iOS
    // layoutSubviews syncing the Unity frame to self.bounds).
    override val shouldUseAndroidLayout: Boolean = true

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
            // Unity already initialized — reparent into this container
            bridge.reparentInto(this)
        } else {
            // Initialize Unity, then reparent once engine is ready.
            // Use postDelayed to give the engine time to boot before
            // reparenting (avoids window detach timeout).
            bridge.initialize(activity) {
                postDelayed({
                    bridge.reparentInto(this)
                }, 3000)
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
                bridge.detachView()
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
