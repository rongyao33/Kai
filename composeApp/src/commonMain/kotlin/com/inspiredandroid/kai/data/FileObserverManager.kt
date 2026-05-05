package com.inspiredandroid.kai.data

expect class FileObserverManager {
    fun startWatching(paths: List<String>)
    fun stopWatching()
    fun isWatching(): Boolean
}

expect fun createFileObserverManager(onFileChanged: (String) -> Unit): FileObserverManager