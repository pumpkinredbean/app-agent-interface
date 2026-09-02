package io.github.pumpkinredbean.appagent.inspector

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import io.github.pumpkinredbean.appagent.ToolAuditSubscription
import io.github.pumpkinredbean.appagent.ToolRuntime
import java.util.concurrent.atomic.AtomicBoolean

public data class InspectorConfiguration @JvmOverloads constructor(
    val openOnShake: Boolean = true,
    val maxEntries: Int = 200,
) {
    init {
        require(maxEntries in 1..10_000) { "maxEntries must be between 1 and 10000" }
    }
}

public interface InspectorHandle : AutoCloseable {
    /** Opens the inspector even when the shake gesture is disabled or unavailable. */
    public fun open()

    /** Detaches the audit listener and foreground shake detector. */
    override fun close()
}

/**
 * Installs the debug audit inspector on one [ToolRuntime].
 *
 * Installing again replaces the previous process-wide installation. The
 * inspector keeps only a bounded, in-memory history for the current process.
 */
public object AppAgentInspector {
    private var activeHandle: RealInspectorHandle? = null

    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun install(
        application: Application,
        runtime: ToolRuntime,
        configuration: InspectorConfiguration = InspectorConfiguration(),
    ): InspectorHandle {
        activeHandle?.close()

        val appName = application.applicationInfo
            .loadLabel(application.packageManager)
            .toString()
            .ifBlank { application.packageName }
        val store = InspectorAuditStore(
            appName = appName,
            maxEntries = configuration.maxEntries,
        )
        val subscription = runtime.addAuditListener(store)
        val shakeDetector = if (configuration.openOnShake) {
            ForegroundShakeDetector(application) { activity ->
                launch(activity ?: application)
            }.also(ForegroundShakeDetector::start)
        } else {
            null
        }
        val handle = RealInspectorHandle(
            application = application,
            store = store,
            subscription = subscription,
            shakeDetector = shakeDetector,
        )
        InspectorRegistry.attach(store)
        activeHandle = handle
        return handle
    }

    @Synchronized
    private fun detach(handle: RealInspectorHandle) {
        if (activeHandle === handle) {
            InspectorRegistry.detach(handle.store)
            activeHandle = null
        }
    }

    private fun launch(context: Context) {
        val intent = Intent(context, InspectorActivity::class.java).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private class RealInspectorHandle(
        private val application: Application,
        val store: InspectorAuditStore,
        private val subscription: ToolAuditSubscription,
        private val shakeDetector: ForegroundShakeDetector?,
    ) : InspectorHandle {
        private val closed: AtomicBoolean = AtomicBoolean(false)

        override fun open() {
            if (!closed.get()) launch(application)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                subscription.close()
                shakeDetector?.close()
                store.markDetached()
                detach(this)
            }
        }
    }
}

internal object InspectorRegistry {
    @Volatile
    var store: InspectorAuditStore? = null
        private set

    fun attach(store: InspectorAuditStore) {
        this.store = store
    }

    fun detach(store: InspectorAuditStore) {
        if (this.store === store) this.store = null
    }
}
