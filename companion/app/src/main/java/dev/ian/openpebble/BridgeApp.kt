package dev.ian.openpebble

import android.app.Application
import android.content.IntentFilter
import android.os.Build
import dev.ian.openpebble.pebble.PebbleBridge
import dev.ian.openpebble.pebble.PebbleReceiver

/**
 * Registers the AppMessage receiver at runtime.
 *
 * The Core phone app relays watch messages as *implicit* broadcasts, which
 * Android 8+ never delivers to manifest-declared receivers — so without this
 * the bridge would be deaf. Registration here covers every process start, and
 * the manifest receiver entry stays as a fallback for phone apps that route
 * explicitly (like the legacy Pebble app).
 */
class BridgeApp : Application() {

    private val receiver = PebbleReceiver()

    override fun onCreate() {
        super.onCreate()
        register()
    }

    fun register() {
        runCatching {
            val filter = IntentFilter(PebbleBridge.ACTION_RECEIVE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }
}
