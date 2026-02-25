package expo.modules.unity

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.expounity.bridge.NativeCallProxy
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import com.unity3d.player.UnityPlayerForActivityOrService

/**
 * Singleton managing the UnityPlayer lifecycle.
 * Android equivalent of ios/UnityBridge.mm.
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
     * Returns the UnityPlayer's FrameLayout container for embedding.
     * Unity 6+ made UnityPlayer abstract; getFrameLayout() returns the full
     * container with the rendering surface inside it.
     */
    val unityPlayerView: View?
        get() = unityPlayer?.frameLayout

    fun initialize(activity: Activity) {
        if (isInitialized) return

        val runInit = Runnable {
            try {
                val player = UnityPlayerForActivityOrService(activity, this)
                unityPlayer = player

                // Start Unity rendering — without these calls the player
                // stays in a paused/unfocused state and shows a blank screen.
                player.resume()
                player.windowFocusChanged(true)

                NativeCallProxy.registerListener(this)

                Log.i(TAG, "Unity initialized")
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
