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
 * Uses a "background parking" pattern: Unity is always attached to the
 * Activity's content view (at 1x1px, Z=-1) so it stays alive. When a
 * React Native view wants to show Unity, we reparent the FrameLayout
 * into that view. When it unmounts, we park it back in the background.
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

    /**
     * Returns the UnityPlayer's FrameLayout for embedding.
     * UnityPlayerForActivityOrService creates its own rendering surface internally.
     */
    val unityPlayerView: FrameLayout?
        get() = unityPlayer?.frameLayout

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

                // Give Unity time to initialize its rendering pipeline
                Thread.sleep(1000)

                // Park the Unity view in the background (1x1px, behind everything)
                addUnityViewToBackground(activity)

                // Kick-start rendering
                player.windowFocusChanged(true)
                player.frameLayout?.requestFocus()
                player.resume()

                // Restore fullscreen state if Unity changed it
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
     * Parks the Unity view in the Activity's content view at 1x1 pixels
     * behind all other views (Z=-1). This keeps Unity alive but invisible.
     */
    private fun addUnityViewToBackground(activity: Activity) {
        val frame = unityPlayerView ?: return

        // Remove from current parent if any
        (frame.parent as? ViewGroup)?.let { parent ->
            parent.endViewTransition(frame)
            parent.removeView(frame)
        }

        frame.z = -1f

        val layoutParams = ViewGroup.LayoutParams(1, 1)
        activity.addContentView(frame, layoutParams)
        Log.i(TAG, "Unity view parked in background")
    }

    /**
     * Moves the Unity view from wherever it currently is into the
     * specified ViewGroup with MATCH_PARENT layout. Called when the
     * React Native component mounts.
     */
    fun addUnityViewToGroup(group: ViewGroup) {
        val frame = unityPlayerView ?: return

        // Remove from current parent
        (frame.parent as? ViewGroup)?.removeView(frame)

        val layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        group.addView(frame, 0, layoutParams)

        unityPlayer?.windowFocusChanged(true)
        frame.requestFocus()
        unityPlayer?.resume()

        Log.i(TAG, "Unity view moved to visible container")
    }

    /**
     * Parks the Unity view back to the background. Called when the
     * React Native component unmounts.
     */
    fun parkUnityViewInBackground() {
        val frame = unityPlayerView ?: return
        val activity = frame.context as? Activity ?: return

        (frame.parent as? ViewGroup)?.let { parent ->
            parent.endViewTransition(frame)
            parent.removeView(frame)
        }

        frame.z = -1f

        val layoutParams = ViewGroup.LayoutParams(1, 1)
        activity.addContentView(frame, layoutParams)
        Log.i(TAG, "Unity view parked back to background")
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
