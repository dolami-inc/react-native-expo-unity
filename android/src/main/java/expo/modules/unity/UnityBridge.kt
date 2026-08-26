package expo.modules.unity

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
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

        /** Cap on buffered Unity → RN messages held while no sink is attached. */
        private const val MAX_PENDING_MESSAGES = 128

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

    @Volatile
    var unityPlayer: UnityPlayer? = null
        private set

    // Set when unload() failed with the engine half-booted. The abandoned
    // player still owns the process's single Unity runtime slot, so
    // initialize() must never construct another one in this process.
    @Volatile
    private var playerAbandoned = false

    /**
     * View-bound sink for Unity → RN messages. Set when an ExpoUnityView
     * attaches and nulled when it detaches (onDetachedFromWindow).
     *
     * Unity keeps running while parked in the Activity background, so it can
     * emit messages while no view is attached (e.g. the user navigated off the
     * camera tab, or during the mount/reparent gap). Rather than dropping those
     * silently, they are buffered in [pendingMessages] and flushed — in arrival
     * order — the moment a sink is (re)attached.
     */
    /** Buffers Unity → RN messages emitted while no [onMessage] sink is attached. */
    private val pendingMessages = ArrayDeque<String>()
    private val pendingLock = Any()

    // Backing field for [onMessage], guarded by [pendingLock]. `onMessage` is
    // invoked on the Unity player thread while the sink is set/cleared on the
    // main thread, so the "is a sink attached?" check and the buffer/flush must
    // be mutually exclusive — otherwise a message read as sink-less can be
    // buffered *after* the setter already flushed, stranding it until the next
    // attach (Android-only, intermittent lost messages such as `image_taken`).
    private var messageSink: ((String) -> Unit)? = null

    var onMessage: ((String) -> Unit)?
        get() = synchronized(pendingLock) { messageSink }
        set(value) {
            var drained: List<String>? = null
            synchronized(pendingLock) {
                messageSink = value
                if (value != null && pendingMessages.isNotEmpty()) {
                    drained = pendingMessages.toList()
                    pendingMessages.clear()
                }
            }
            // Deliver the drained backlog outside the lock, in arrival order.
            val toDeliver = drained
            if (value != null && toDeliver != null) {
                val deliver = Runnable { toDeliver.forEach { value(it) } }
                if (Looper.myLooper() == Looper.getMainLooper()) deliver.run()
                else mainHandler.post(deliver)
            }
        }

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
        if (playerAbandoned) {
            Log.e(TAG, "Unity player was abandoned after a failed unload; refusing to re-initialize in this process")
            return
        }
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

        // Unity fits the system navigation-bar inset into its frame (and, without
        // the unity.render-outside-safearea manifest flag, the display cutout),
        // leaving a gap between the Unity surface and the RN-assigned bounds.
        // Consume the system-window insets on the Unity frame so it fills the full
        // container, matching iOS where unityView.frame is pinned to self.bounds.
        frame.fitsSystemWindows = false
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }
        frame.requestApplyInsets()

        // Kick a layout pass so the Unity surface sizes to the container's
        // bounds. ExpoUnityView has shouldUseAndroidLayout = true, so this
        // schedules a real Android measure/layout that resizes the surface
        // (a plain measure/layout call wouldn't refresh the SurfaceView buffer).
        container.requestLayout()

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
            try {
                unityPlayer?.unload()
                Log.i(TAG, "unload completed")
            } catch (e: LinkageError) {
                // Unity's JNI natives were never registered — the engine never
                // finished booting. In 90 days of Crashlytics data (Pier) this
                // fired only on x86_64 devices with spoofed fingerprints, and
                // unload() was the only native entry point that ever threw.
                // Drop the player rather than crash, and mark it abandoned:
                // onUnityPlayerUnloaded will never fire for it, so a
                // re-initialize would construct a second Unity runtime.
                Log.e(TAG, "unload failed; Unity natives not registered", e)
                playerAbandoned = true
                unityPlayer = null
            }
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
        // Hold [pendingLock] across the sink check AND the buffer write so a
        // concurrent setter (main thread) can't flush between them and strand
        // this message. The sink itself only posts to the main handler, so
        // delivering under the lock is cheap and non-blocking.
        synchronized(pendingLock) {
            val sink = messageSink
            if (sink != null) {
                sink(message)
            } else {
                // No view attached — buffer instead of dropping. Flushed on attach.
                if (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                    pendingMessages.removeFirst()
                }
                pendingMessages.addLast(message)
            }
        }
    }
}
