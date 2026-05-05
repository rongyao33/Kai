package com.inspiredandroid.kai.data

actual class FileObserverManager(
    private val onFileChanged: (String) -> Unit,
) {
    actual fun startWatching(paths: List<String>) {}
    actual fun stopWatching() {}
    actual fun isWatching(): Boolean = false
}

actual fun createFileObserverManager(onFileChanged: (String) -> Unit): FileObserverManager {
    return FileObserverManager(onFileChanged)
}