package io.github.pumpkinredbean.appagent.ui

/** How a newly produced frame changes the current navigation stack. */
public enum class UiFrameNavigationMode {
    PUSH,
    REPLACE,
}

/** Lifecycle delivered to the host renderer for its frame-owned resources. */
public enum class UiFrameLifecycleState {
    PAUSED,
    RESUMED,
    DISPOSED,
}

public data class UiFrameLifecycleEvent(
    val frame: UiFrame,
    val state: UiFrameLifecycleState,
)

public fun interface UiFrameLifecycleObserver {
    public fun onLifecycleChanged(event: UiFrameLifecycleEvent)
}

/** Immutable frame-stack state emitted after a navigation or lifecycle change. */
public data class UiFrameNavigationSnapshot(
    val frames: List<UiFrame>,
    val currentResumed: Boolean,
) {
    public val current: UiFrame?
        get() = frames.lastOrNull()

    public val depth: Int
        get() = frames.size
}

public fun interface UiFrameNavigationObserver {
    public fun onNavigationChanged(snapshot: UiFrameNavigationSnapshot)
}

/** Removes a navigation observer when closed. */
public class UiFrameNavigationSubscription internal constructor(
    private val observers: MutableList<UiFrameNavigationObserver>,
    private val observer: UiFrameNavigationObserver,
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        observers.removeAll { it === observer }
    }
}

/**
 * Navigation state for application-defined UI frames.
 *
 * Identity is the canonical combination of [UiFrame.tag] and
 * [UiFrame.params]. A PUSH of an identity already in the stack removes the old
 * occurrence and moves the new frame to the top without growing the stack.
 *
 * Call this coordinator from one host event thread. Lifecycle callbacks are
 * delivered synchronously in navigation order.
 */
public class UiFrameNavigator @JvmOverloads constructor(
    private val lifecycleObserver: UiFrameLifecycleObserver = UiFrameLifecycleObserver {},
) : AutoCloseable {
    private val mutableFrames: MutableList<UiFrame> = mutableListOf()
    private val navigationObservers: MutableList<UiFrameNavigationObserver> = mutableListOf()
    private var currentResumed: Boolean = false
    private var closed: Boolean = false

    public val frames: List<UiFrame>
        get() = mutableFrames.toList()

    public val current: UiFrame?
        get() = mutableFrames.lastOrNull()

    public val depth: Int
        get() = mutableFrames.size

    /** Current immutable state for renderers and platform adapters. */
    public val snapshot: UiFrameNavigationSnapshot
        get() = UiFrameNavigationSnapshot(
            frames = frames,
            currentResumed = currentResumed,
        )

    /**
     * Observes stack and current-frame lifecycle state.
     *
     * The current snapshot is delivered immediately. Later callbacks are
     * synchronous and run on the same host event thread as the mutation.
     */
    public fun observe(observer: UiFrameNavigationObserver): UiFrameNavigationSubscription {
        checkOpen()
        if (navigationObservers.none { it === observer }) navigationObservers += observer
        observer.onNavigationChanged(snapshot)
        return UiFrameNavigationSubscription(navigationObservers, observer)
    }

    public fun navigate(
        frame: UiFrame,
        mode: UiFrameNavigationMode = UiFrameNavigationMode.PUSH,
    ) {
        checkOpen()
        when (mode) {
            UiFrameNavigationMode.PUSH -> push(frame)
            UiFrameNavigationMode.REPLACE -> replace(frame)
        }
        notifyNavigationChanged()
    }

    /** Pauses the current frame before a new agent turn begins. */
    public fun onTurnStarted() {
        checkOpen()
        if (pauseCurrent()) notifyNavigationChanged()
    }

    /** Resumes the current frame without changing navigation state. */
    public fun resumeCurrent() {
        checkOpen()
        if (resumeCurrentInternal()) notifyNavigationChanged()
    }

    private fun resumeCurrentInternal(): Boolean {
        val frame = current ?: return false
        if (currentResumed) return false
        currentResumed = true
        dispatch(frame, UiFrameLifecycleState.RESUMED)
        return true
    }

    /** Pops the top frame and resumes the frame below it. The root frame is retained. */
    public fun back(): Boolean {
        checkOpen()
        if (mutableFrames.size <= 1) return false
        val removed = mutableFrames.removeLast()
        if (currentResumed) dispatch(removed, UiFrameLifecycleState.PAUSED)
        dispatch(removed, UiFrameLifecycleState.DISPOSED)
        currentResumed = false
        resumeCurrentInternal()
        notifyNavigationChanged()
        return true
    }

    /** Disposes every frame and leaves an empty reusable navigator. */
    public fun clear() {
        checkOpen()
        if (currentResumed) current?.let { dispatch(it, UiFrameLifecycleState.PAUSED) }
        mutableFrames.asReversed().forEach { dispatch(it, UiFrameLifecycleState.DISPOSED) }
        mutableFrames.clear()
        currentResumed = false
        notifyNavigationChanged()
    }

    /** Permanently disposes this navigator. */
    override fun close() {
        if (closed) return
        clear()
        closed = true
        navigationObservers.clear()
    }

    private fun push(frame: UiFrame) {
        val previous = current
        val existingIndex = mutableFrames.indexOfFirst { it.key == frame.key }
        val alreadyTop = existingIndex >= 0 && existingIndex == mutableFrames.lastIndex

        if (alreadyTop) {
            mutableFrames[mutableFrames.lastIndex] = frame
            if (!currentResumed) resumeCurrentInternal()
            return
        }

        if (currentResumed) previous?.let { dispatch(it, UiFrameLifecycleState.PAUSED) }
        if (existingIndex >= 0) {
            val duplicate = mutableFrames.removeAt(existingIndex)
            dispatch(duplicate, UiFrameLifecycleState.DISPOSED)
        }
        mutableFrames.add(frame)
        currentResumed = false
        resumeCurrentInternal()
    }

    private fun replace(frame: UiFrame) {
        val previous = current
        if (previous == null) {
            mutableFrames.add(frame)
            resumeCurrentInternal()
            return
        }

        if (previous.key == frame.key) {
            mutableFrames[mutableFrames.lastIndex] = frame
            if (!currentResumed) resumeCurrentInternal()
            return
        }

        if (currentResumed) dispatch(previous, UiFrameLifecycleState.PAUSED)
        dispatch(previous, UiFrameLifecycleState.DISPOSED)
        mutableFrames.removeLast()

        val duplicateIndex = mutableFrames.indexOfFirst { it.key == frame.key }
        if (duplicateIndex >= 0) {
            val duplicate = mutableFrames.removeAt(duplicateIndex)
            dispatch(duplicate, UiFrameLifecycleState.DISPOSED)
        }
        mutableFrames.add(frame)
        currentResumed = false
        resumeCurrentInternal()
    }

    private fun pauseCurrent(): Boolean {
        val frame = current ?: return false
        if (!currentResumed) return false
        currentResumed = false
        dispatch(frame, UiFrameLifecycleState.PAUSED)
        return true
    }

    private fun dispatch(frame: UiFrame, state: UiFrameLifecycleState) {
        lifecycleObserver.onLifecycleChanged(UiFrameLifecycleEvent(frame, state))
    }

    private fun notifyNavigationChanged() {
        if (navigationObservers.isEmpty()) return
        val next = snapshot
        navigationObservers.toList().forEach { it.onNavigationChanged(next) }
    }

    private fun checkOpen() {
        check(!closed) { "UiFrameNavigator is closed" }
    }
}
