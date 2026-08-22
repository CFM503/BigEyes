package com.bigeyes.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

class BigEyesApplication : Application() {

    companion object {
        private const val TAG = "BigEyesApplication"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed enabling vector compat: ${e.message}")
        }

        // Global crash guard to log any unhandled fatal exceptions to logcat
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
