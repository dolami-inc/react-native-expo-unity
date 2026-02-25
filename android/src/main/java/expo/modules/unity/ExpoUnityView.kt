package expo.modules.unity

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
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

        if (!bridge.isInitialized) {
            bridge.initialize(activity)
        }

        bridge.onMessage = { message ->
            post {
                onUnityMessage(mapOf("message" to message))
            }
        }

        mountUnityView()
    }

    private fun mountUnityView() {
        val playerView = UnityBridge.getInstance().unityPlayerView ?: run {
            Log.w(TAG, "Unity player view not available yet")
            return
        }

        // Remove from previous parent if needed
        (playerView.parent as? ViewGroup)?.removeView(playerView)

        addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        Log.i(TAG, "Unity view mounted")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Start rendering after the view is in the window hierarchy,
        // so Unity's surface is properly connected to the display.
        val bridge = UnityBridge.getInstance()
        if (bridge.isInitialized) {
            bridge.startRendering()
        }
    }

    override fun onDetachedFromWindow() {
        val bridge = UnityBridge.getInstance()
        bridge.onMessage = null

        if (bridge.isInitialized) {
            if (autoUnloadOnUnmount) {
                bridge.unload()
                Log.i(TAG, "Auto-unloaded (view detached)")
            } else {
                bridge.setPaused(true)
                Log.i(TAG, "Auto-paused on unmount (autoUnloadOnUnmount=false)")
            }
        }

        super.onDetachedFromWindow()
    }
}
