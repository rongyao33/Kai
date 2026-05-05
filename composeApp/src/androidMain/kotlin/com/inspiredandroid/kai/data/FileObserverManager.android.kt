package com.inspiredandroid.kai.data

import android.os.FileObserver
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

actual class FileObserverManager(
    private val onFileChanged: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableMapOf<String, FileObserver>()
    private var watching = false

    actual fun startWatching(paths: List<String>) {
        if (watching) {
            Logger.d("FileObserverManager", "Already watching")
            return
        }

        watching = true

        for (path in paths) {
            val file = File(path)
            if (!file.exists()) {
                Logger.w("FileObserverManager", "Path does not exist: $path")
                continue
            }

            val observer = createFileObserver(path) { eventPath ->
                scope.launch {
                    handleFileEvent(eventPath)
                }
            }

            observers[path] = observer
            observer.startWatching()
            Logger.d("FileObserverManager", "Started watching: $path")
        }
    }

    actual fun stopWatching() {
        if (!watching) return

        for ((path, observer) in observers) {
            observer.stopWatching()
            Logger.d("FileObserverManager", "Stopped watching: $path")
        }
        observers.clear()
        watching = false
    }

    actual fun isWatching(): Boolean = watching

    private fun createFileObserver(path: String, onEvent: (String) -> Unit): FileObserver {
        return object : FileObserver(path, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return

                val fullPath = if (path.startsWith("/")) path else "$path/$path"

                when {
                    event and CREATE != 0 -> {
                        Logger.d("FileObserverManager", "CREATE: $fullPath")
                        onEvent(fullPath)
                    }
                    event and MODIFY != 0 -> {
                        Logger.d("FileObserverManager", "MODIFY: $fullPath")
                        onEvent(fullPath)
                    }
                    event and DELETE != 0 -> {
                        Logger.d("FileObserverManager", "DELETE: $fullPath")
                    }
                    event and MOVED_FROM != 0 -> {
                        Logger.d("FileObserverManager", "MOVED_FROM: $fullPath")
                    }
                    event and MOVED_TO != 0 -> {
                        Logger.d("FileObserverManager", "MOVED_TO: $fullPath")
                        onEvent(fullPath)
                    }
                }
            }
        }
    }

    private suspend fun handleFileEvent(path: String) {
        val extension = path.substringAfterLast(".", "").lowercase()
        val supportedExtensions = listOf("pdf", "docx", "doc", "txt", "md", "jpg", "jpeg", "png", "gif", "webp", "bmp")

        if (extension !in supportedExtensions) {
            return
        }

        val file = File(path)
        if (!file.exists()) {
            return
        }

        try {
            Logger.d("FileObserverManager", "Indexing changed file: $path")
            onFileChanged(path)
        } catch (e: Exception) {
            Logger.e("FileObserverManager", "Failed to index file: ${e.message}")
        }
    }
}

actual fun createFileObserverManager(onFileChanged: (String) -> Unit): FileObserverManager {
    return FileObserverManager(onFileChanged)
}