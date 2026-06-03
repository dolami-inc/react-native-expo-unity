package expo.modules.unity

import android.content.Context
import android.util.Log
import android.view.View
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Keep the Unity surface sized to this view's bounds (Android
        // equivalent of iOS layoutSubviews). React Native does not lay out
        // natively-added children, so the Unity FrameLayout — added with
        // MATCH_PARENT while parked full-screen in the Activity content view —
        // otherwise keeps that full-screen size and overflows behind the
        // bottom tab bar instead of shrinking to fit above it.
        val frame = UnityBridge.getInstance().unityPlayerView ?: return
        if (frame.parent !== this) return
        layoutUnityFrame(frame, right - left, bottom - top)
    }

    private fun layoutUnityFrame(frame: View, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frame.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        frame.layout(0, 0, width, height)
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
