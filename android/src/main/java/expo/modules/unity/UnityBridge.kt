package expo.modules.unity

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.expounity.bridge.NativeCallProxy
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import com.unity3d.player.UnityPlayerForActivityOrService

/**
 * Singleton managing the UnityPlayer lifecycle.
 * Android equivalent of ios/UnityBridge.mm.
 *
 * Unity 6's engine only boots when the view is in the Activity's content
 * view hierarchy. We park the view at MATCH_PARENT behind everything (Z=-1)
 * to let the engine start, then reparent into the React Native container.
 */
class UnityBridge private constructor() : IUnityPlayerLifecycleEvents, NativeCallProxy.MessageListener {

    companion object {
        private const val TAG = "ExpoUnity"

        @Volatile
        private var instance: UnityBridge? = null

        @JvmStatic
        fun getInstance(): UnityBridge {
            return instance ?: synchronized(this) {
                instance ?: UnityBridge().also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    var unityPlayer: UnityPlayer? = null
        private set

    var onMessage: ((String) -> Unit)? = null

    /** Tracked here (not on the Module) so it survives module recreation. */
    var wasRunningBeforeBackground: Boolean = false

    var isReady: Boolean = false
        private set

    val isInitialized: Boolean
        get() = unityPlayer != null

    val unityPlayerView: FrameLayout?
        get() = unityPlayer?.frameLayout

    /**
     * Creates the Unity player, parks it in the Activity's content view
     * (behind everything) to let the engine start, then fires [onReady].
     */
    fun initialize(activity: Activity, onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }

        val runInit = Runnable {
            try {
                activity.window.setFormat(PixelFormat.RGBA_8888)

                val flags = activity.window.attributes.flags
                val wasFullScreen = (flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0

                val player = UnityPlayerForActivityOrService(activity, this)
                unityPlayer = player

                NativeCallProxy.registerListener(this)
                Log.i(TAG, "Unity player created")

                // Park in Activity's content view at full size but behind
                // everything. Unity's engine only starts when the view is
                // in the Activity's window hierarchy.
                val frame = player.frameLayout
                frame.z = -1f
                activity.addContentView(frame, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                Log.i(TAG, "Unity view parked in Activity (background)")

                // Start the rendering pipeline
                player.windowFocusChanged(true)
                frame.requestFocus()
                player.resume()

                // Restore fullscreen state
                if (!wasFullScreen) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }

                isReady = true
                Log.i(TAG, "Unity initialized and ready")

                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Unity", e)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runInit.run()
        } else {
            mainHandler.post(runInit)
        }
    }

    /**
     * Moves the Unity view from the Activity background into the given
     * container. Called when the React Native component is ready to show Unity.
     */
    fun reparentInto(container: ViewGroup) {
        val frame = unityPlayerView ?: run {
            Log.w(TAG, "Unity player view not available")
            return
        }

        // Remove from Activity's content view
        (frame.parent as? ViewGroup)?.let { parent ->
            parent.endViewTransition(frame)
            parent.removeView(frame)
        }

        // Reset Z and add to the React Native container
        frame.z = 0f
        container.addView(frame, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Re-kick rendering after reparenting
        unityPlayer?.windowFocusChanged(true)
        frame.requestFocus()
        unityPlayer?.resume()

        Log.i(TAG, "Unity view reparented into container")
    }

    /**
     * Detaches the Unity view from its current parent.
     */
    fun detachView() {
        val frame = unityPlayerView ?: return
        (frame.parent as? ViewGroup)?.let { parent ->
            parent.endViewTransition(frame)
            parent.removeView(frame)
        }
        Log.i(TAG, "Unity view detached")
    }

    fun sendMessage(gameObject: String, methodName: String, message: String) {
        if (!isInitialized) return
        UnityPlayer.UnitySendMessage(gameObject, methodName, message)
    }

    fun setPaused(paused: Boolean) {
        if (!isInitialized) return
        val action = Runnable {
            if (paused) {
                unityPlayer?.pause()
            } else {
                unityPlayer?.resume()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    fun unload() {
        if (!isInitialized) return
        isReady = false
        Log.i(TAG, "unload called")
        val action = Runnable {
            unityPlayer?.unload()
            Log.i(TAG, "unload completed")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    // IUnityPlayerLifecycleEvents

    override fun onUnityPlayerUnloaded() {
        Log.i(TAG, "onUnityPlayerUnloaded")
        unityPlayer = null
        isReady = false
    }

    override fun onUnityPlayerQuitted() {
        Log.i(TAG, "onUnityPlayerQuitted")
        unityPlayer = null
        isReady = false
    }

    // NativeCallProxy.MessageListener (Unity -> RN)

    override fun onMessage(message: String) {
        onMessage?.invoke(message)
    }
}
