package io.github.pumpkinredbean.appagent.inspector

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.sqrt

internal class ForegroundShakeDetector(
    private val application: Application,
    private val onShake: (Activity?) -> Unit,
) : Application.ActivityLifecycleCallbacks, SensorEventListener, AutoCloseable {
    private val sensorManager: SensorManager? =
        application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val resumedActivities: MutableSet<Activity> =
        Collections.newSetFromMap(IdentityHashMap())
    private var foregroundActivity: WeakReference<Activity>? = null
    private var sensorRegistered: Boolean = false
    private val gestureDetector: ShakeSequenceDetector = ShakeSequenceDetector()

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities += activity
        foregroundActivity = WeakReference(activity)
        if (!sensorRegistered && accelerometer != null) {
            sensorRegistered = sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI,
            ) == true
        }
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities -= activity
        if (foregroundActivity?.get() === activity) {
            foregroundActivity = resumedActivities.lastOrNull()?.let(::WeakReference)
        }
        if (resumedActivities.isEmpty()) {
            unregisterSensor()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return

        val gravity = SensorManager.GRAVITY_EARTH
        val x = event.values[0] / gravity
        val y = event.values[1] / gravity
        val z = event.values[2] / gravity
        val force = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        val activity = foregroundActivity?.get()
        if (gestureDetector.record(force, now) && activity !is InspectorActivity) {
            onShake(activity)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() {
        application.unregisterActivityLifecycleCallbacks(this)
        resumedActivities.clear()
        foregroundActivity = null
        unregisterSensor()
    }

    private fun unregisterSensor() {
        if (sensorRegistered) {
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities -= activity
        if (foregroundActivity?.get() === activity) {
            foregroundActivity = resumedActivities.lastOrNull()?.let(::WeakReference)
        }
        if (resumedActivities.isEmpty()) unregisterSensor()
    }
}

internal class ShakeSequenceDetector(
    private val forceThreshold: Float = 2.7f,
    private val requiredHits: Int = 2,
    private val sequenceWindowMillis: Long = 500L,
    private val triggerDebounceMillis: Long = 1_200L,
) {
    private var hitCount: Int = 0
    private var lastHitAt: Long? = null
    private var lastTriggeredAt: Long? = null

    fun record(force: Float, nowMillis: Long): Boolean {
        if (force < forceThreshold) return false

        val previousHit = lastHitAt
        hitCount = if (previousHit != null && nowMillis - previousHit <= sequenceWindowMillis) {
            hitCount + 1
        } else {
            1
        }
        lastHitAt = nowMillis

        val previousTrigger = lastTriggeredAt
        val debounced = previousTrigger != null && nowMillis - previousTrigger < triggerDebounceMillis
        if (hitCount < requiredHits || debounced) return false

        hitCount = 0
        lastTriggeredAt = nowMillis
        return true
    }
}
