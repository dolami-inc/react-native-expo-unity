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
 * Creates the Unity player and lets the ExpoUnityView add the
 * FrameLayout directly — no background parking, since Unity 6's
 * window management times out when reparenting from a background view.
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

    val isInitialized: Boolean
        get() = unityPlayer != null

    /**
     * Returns the UnityPlayer's FrameLayout for embedding.
     * UnityPlayerForActivityOrService creates its own rendering surface internally.
     */
    val unityPlayerView: FrameLayout?
        get() = unityPlayer?.frameLayout

    /**
     * Creates the UnityPlayer. The caller is responsible for adding
     * [unityPlayerView] to the view hierarchy immediately after [onReady] fires.
     */
    fun initialize(activity: Activity, onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }

        val runInit = Runnable {
            try {
                // Set RGBA_8888 format for proper rendering
                activity.window.setFormat(PixelFormat.RGBA_8888)

                // Save fullscreen state before Unity potentially changes it
                val flags = activity.window.attributes.flags
                val wasFullScreen = (flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0

                val player = UnityPlayerForActivityOrService(activity, this)
                unityPlayer = player

                NativeCallProxy.registerListener(this)
                Log.i(TAG, "Unity player created")

                // Restore fullscreen state if Unity changed it
                if (!wasFullScreen) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }

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
     * Adds the Unity FrameLayout to the given container and starts rendering.
     * Must be called after [initialize] completes.
     */
    fun attachToContainer(container: ViewGroup) {
        val frame = unityPlayerView ?: run {
            Log.w(TAG, "Unity player view not available")
            return
        }

        // Remove from current parent if any
        (frame.parent as? ViewGroup)?.removeView(frame)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(frame, 0, layoutParams)
        Log.i(TAG, "Unity view attached to container")

        // Kick-start rendering after the view is in the hierarchy.
        // Use post to let the layout pass complete first.
        frame.post {
            unityPlayer?.windowFocusChanged(true)
            frame.requestFocus()
            unityPlayer?.resume()
            Log.i(TAG, "Rendering started")
        }
    }

    /**
     * Detaches the Unity view from its current parent without destroying it.
     */
    fun detachFromContainer() {
        val frame = unityPlayerView ?: return
        (frame.parent as? ViewGroup)?.removeView(frame)
        Log.i(TAG, "Unity view detached from container")
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
    }

    override fun onUnityPlayerQuitted() {
        Log.i(TAG, "onUnityPlayerQuitted")
        unityPlayer = null
    }

    // NativeCallProxy.MessageListener (Unity -> RN)

    override fun onMessage(message: String) {
        onMessage?.invoke(message)
    }
}
