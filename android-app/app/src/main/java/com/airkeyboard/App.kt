package com.airkeyboard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.PasswordStore
import com.airkeyboard.ui.common.AppPrefs
import com.airkeyboard.ui.common.ConnectionGuard
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.airmouse.AirMouseActivity
import com.airkeyboard.ui.apps.AppsActivity
import com.airkeyboard.ui.bookmarks.BookmarksActivity
import com.airkeyboard.ui.keyboard.KeyboardActivity
import com.airkeyboard.ui.presentation.PresentationActivity
import com.airkeyboard.ui.remotes.RemotesActivity
import com.airkeyboard.ui.trackpad.TrackpadActivity
import timber.log.Timber

/**
 * Application class for global setup
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        setupLogging()
        PasswordStore.init(this)
        AppCompatDelegate.setDefaultNightMode(AppPrefs.nightMode(this))
        registerActivityLifecycleCallbacks(KeepAwake)
        registerActivityLifecycleCallbacks(NeedsConnection)
        routeLostConnections()
    }

    /**
     * Whatever screen is open when the session is lost, go to the connection screen (as the only
     * screen left) and let it reconnect to the same PC. With the app in the background Android may
     * not start it; the control screens then find the dead client when they resume.
     */
    private fun routeLostConnections() {
        val main = Handler(Looper.getMainLooper())
        ConnectionManager.onConnectionLost = { host, port ->
            main.post {
                // Disconnected or connected again meanwhile: nothing to do
                val c = ConnectionManager.client
                if (c == null || c.isConnectedStatus()) return@post
                glassToast(R.string.disconnected_from_server)
                startActivity(ConnectionGuard.connectionScreen(this, host, port))
            }
        }
    }

    private fun setupLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    /**
     * A control screen that comes back without a live session (lost while the app was in the
     * background, or after process death) goes to the connection screen instead of showing a dead one
     */
    private object NeedsConnection : ActivityLifecycleCallbacks {
        private val screens: Set<Class<out Activity>> = setOf(
            TrackpadActivity::class.java, KeyboardActivity::class.java, RemotesActivity::class.java,
            PresentationActivity::class.java, AppsActivity::class.java, BookmarksActivity::class.java,
            AirMouseActivity::class.java
        )

        override fun onActivityResumed(activity: Activity) {
            if (activity.javaClass !in screens) return
            val c = ConnectionManager.client
            if (c?.isConnectedStatus() == true) return
            val message = if (c != null) R.string.disconnected_from_server else R.string.not_connected
            ConnectionGuard.returnToConnection(activity, activity.getString(message))
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Keeps the screen on while any of our screens is in front, if the setting is on */
    private object KeepAwake : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (AppPrefs.keepScreenAwake(activity)) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
