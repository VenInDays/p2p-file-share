package com.p2pfileshare.app.util

import android.util.Log

/**
 * Simple logging helper to avoid repeating tag strings throughout the codebase.
 */
object LogHelper {
    fun d(tag: String, msg: String, t: Throwable? = null) {
        Log.d(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t)
    }

    fun i(tag: String, msg: String, t: Throwable? = null) {
        Log.i(tag, msg, t)
    }
}
