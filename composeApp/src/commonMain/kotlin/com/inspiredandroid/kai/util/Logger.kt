package com.inspiredandroid.kai.util

object Logger {
    private var enabled = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun d(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            println("[$tag] ERROR: $message")
            throwable?.let { println("[$tag] ${it.message}") }
        }
    }

    fun w(tag: String, message: String) {
        if (enabled) println("[$tag] WARN: $message")
    }

    fun i(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }
}
