package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.email.EmailPoller
import com.inspiredandroid.kai.getBackgroundDispatcher
import com.inspiredandroid.kai.isEmailSupported
import com.inspiredandroid.kai.isNotificationsSupported
import com.inspiredandroid.kai.isSmsSupported
import com.inspiredandroid.kai.sendHeartbeatNotification
import com.inspiredandroid.kai.sms.SmsPoller
import com.inspiredandroid.kai.ui.markdown.parseMarkdown
import com.inspiredandroid.kai.ui.markdown.toSpeakableText
import com.inspiredandroid.kai.util.Logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TaskScheduler(
    private val dataRepository: DataRepository,
    private val taskStore: TaskStore?,
    private val appSettings: AppSettings?,
    private val heartbeatManager: HeartbeatManager? = null,
    private val emailStore: EmailStore? = null,
    private val emailPoller: EmailPoller? = null,
    private val smsStore: SmsStore? = null,
    private val smsPoller: SmsPoller? = null,
    private val notificationStore: NotificationStore? = null,
    private val enabled: Boolean = true,
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
) {
    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
        const val MAX_BACKOFF_MS = 3_600_000L
        const val HEARTBEAT_CONTEXT_COUNT = 3
        const val MAX_TASK_LOG_ENTRIES = 10
        const val HEARTBEAT_NOTIFICATION_PREVIEW_CHARS = 240
    }

    private val schedulerScope = CoroutineScope(
        SupervisorJob() + backgroundDispatcher + CoroutineName("TaskScheduler"),
    )

    private var activeJob: Job? = null

    @Volatile
    var isLoadingCheck: () -> Boolean = { false }

    @Volatile
    var appInForeground: Boolean = false

    private fun requireTaskStore(): TaskStore = taskStore
        ?: throw IllegalStateException("TaskStore is required but not initialized")

    private fun requireAppSettings(): AppSettings = appSettings
        ?: throw IllegalStateException("AppSettings is required but not initialized")

    fun start() {
        if (!enabled || taskStore == null || appSettings == null) return
        if (activeJob?.isActive == true) return
        val store = taskStore
        val settings = appSettings
        activeJob = schedulerScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS.milliseconds)
                if (!settings.isSchedulingEnabled()) continue

                val dueTasks = store.getDueTasks()
                for (task in dueTasks) {
                    if (isLoadingCheck()) break

                    try {
                        val response = dataRepository.askWithTools(task.prompt)
                        if (response.isNotBlank()) {
                            val header = task.description.ifBlank { "Scheduled task" }
                            dataRepository.addAssistantMessage("**$header**\n\n$response")
                        }
                        handleTaskCompletion(task, store)
                    } catch (e: Exception) {
                        handleTaskFailure(task, formatException(e), store)
                    }
                }

                if (!isLoadingCheck() && heartbeatManager?.isHeartbeatDue() == true) {
                    runHeartbeat()
                }

                if (!isLoadingCheck() && isEmailSupported && settings.isEmailEnabled() && emailStore != null) {
                    checkNewEmails { isLoadingCheck() }
                }

                if (!isLoadingCheck() && isSmsSupported && settings.isSmsEnabled() && smsStore != null && smsPoller != null) {
                    checkNewSms()
                }
            }
        }
    }

    /**
     * Run one heartbeat cycle: build the prompt, call the AI, record the result, and
     * surface any non-OK response (in-app message + push notification when backgrounded).
     * Used by the scheduler loop's due-check and by [triggerHeartbeatNow] for user-pressed refresh.
     */
    private suspend fun runHeartbeat() {
        val manager = heartbeatManager ?: return
        val pendingEmails = emailStore?.getPending().orEmpty()
        val pendingSms = smsStore?.getPending().orEmpty()
        val pendingNotifications = notificationStore?.getPending().orEmpty()
        try {
            val recentResponses = dataRepository.savedConversations.value
                .find { it.type == Conversation.TYPE_HEARTBEAT }
                ?.messages?.takeLast(HEARTBEAT_CONTEXT_COUNT)
                ?.map { it.content }
                ?: emptyList()
            val heartbeatPrompt = manager.buildHeartbeatPrompt(recentResponses, pendingEmails, pendingSms, pendingNotifications)
            val response = dataRepository.askWithTools(heartbeatPrompt, manager.getConfig().heartbeatInstanceId)
            manager.markHeartbeatExecuted()
            manager.recordHeartbeat(success = true)
            if (response.isNotBlank() && "HEARTBEAT_OK" !in response) {
                dataRepository.addAssistantMessage(response)
                // Push-notify only when the user won't see the in-app banner.
                // Tapping the notification deep-links into the heartbeat
                // conversation via `EXTRA_OPEN_HEARTBEAT` (Android actual).
                // Strip markdown + kai-ui fences before sending to the tray —
                // the notification surface can't render them and raw fence
                // text (```kai-ui {...}```) is unreadable.
                if (!appInForeground) {
                    val preview = truncateForNotification(
                        parseMarkdown(response).toSpeakableText(),
                    )
                    if (preview.isNotBlank()) {
                        sendHeartbeatNotification(
                            title = "Kai heartbeat",
                            body = preview,
                        )
                    }
                }
            }
            // Only clear the snapshot we actually showed to the AI — messages
            // that arrived during the call stay pending for the next heartbeat.
            if (pendingEmails.isNotEmpty()) {
                emailStore?.let { store ->
                    store.removePending(pendingEmails)
                    // Advance the per-account delivery watermark so the user's
                    // next `check_email` call won't re-surface the same UIDs
                    // the heartbeat just summarised.
                    val maxUidByAccount = pendingEmails
                        .groupBy { it.accountId }
                        .mapValues { (_, msgs) -> msgs.maxOf { it.uid } }
                    for ((accId, maxUid) in maxUidByAccount) {
                        val current = store.getSyncState(accId)
                        if (maxUid > current.lastSeenUid) {
                            store.updateSyncState(current.copy(lastSeenUid = maxUid))
                        }
                    }
                }
            }
            if (pendingSms.isNotEmpty()) {
                smsStore?.removePending(pendingSms)
            }
            if (pendingNotifications.isNotEmpty()) {
                notificationStore?.removePending(pendingNotifications)
            }
            // Sweep retention bounds opportunistically after each heartbeat run.
            notificationStore?.sweep()
        } catch (e: Exception) {
            manager.recordHeartbeat(success = false, error = e.message ?: e.toString())
        }
    }

    /**
     * User-pressed manual heartbeat (Settings → Agent → Heartbeat refresh icon). Bypasses
     * the active-hours window and the interval-due check, but still requires heartbeat to
     * be enabled and scheduling overall to be on. No-ops if either is off.
     */
    suspend fun triggerHeartbeatNow() {
        val manager = heartbeatManager ?: return
        if (appSettings?.isSchedulingEnabled() != true) return
        if (!manager.getConfig().enabled) return
        runHeartbeat()
    }

    /**
     * Trims a heartbeat preview to fit a notification body: respects word boundaries
     * when cutting and appends an ellipsis so the user knows more text exists in the
     * conversation. Short inputs pass through unchanged.
     */
    private fun truncateForNotification(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= HEARTBEAT_NOTIFICATION_PREVIEW_CHARS) return trimmed
        val window = trimmed.substring(0, HEARTBEAT_NOTIFICATION_PREVIEW_CHARS)
        val lastSpace = window.lastIndexOf(' ')
        // Only prefer the word boundary if it's close to the cap; otherwise hard-cut —
        // a word boundary 100 chars back would throw away half the preview.
        val cut = if (lastSpace >= HEARTBEAT_NOTIFICATION_PREVIEW_CHARS - 40) lastSpace else window.length
        return window.substring(0, cut).trimEnd().trimEnd(',', ';', ':') + "…"
    }

    private suspend fun checkNewEmails(isLoading: () -> Boolean) {
        if (emailStore == null || appSettings == null || emailPoller == null) return
        val pollMinutes = appSettings.getEmailPollIntervalMinutes()
        if (pollMinutes <= 0) return // 0 = never poll automatically
        val pollIntervalMs = pollMinutes * 60_000L
        val now = Clock.System.now().toEpochMilliseconds()

        for (account in emailStore.getAccounts()) {
            if (isLoading()) break
            val syncState = emailStore.getSyncState(account.id)
            // Rate-limit by last attempt (success or failure) so repeated failures back off
            // at the configured poll interval instead of retrying every scheduler tick.
            val lastActivityMs = maxOf(syncState.lastSyncEpochMs, syncState.lastAttemptEpochMs)
            if (now - lastActivityMs < pollIntervalMs) continue
            emailPoller.poll(account)
        }
    }

    private suspend fun checkNewSms() {
        if (smsStore == null || appSettings == null || smsPoller == null) return
        val pollMinutes = appSettings.getSmsPollIntervalMinutes()
        if (pollMinutes <= 0) return
        val pollIntervalMs = pollMinutes * 60_000L
        val now = Clock.System.now().toEpochMilliseconds()
        val syncState = smsStore.getSyncState()
        val lastActivityMs = maxOf(syncState.lastSyncEpochMs, syncState.lastAttemptEpochMs)
        if (now - lastActivityMs < pollIntervalMs) return
        smsPoller.poll()
    }

    /**
     * Format an exception for the task log. Plain `e.message` collapses too much detail —
     * it's often null (NPE, IllegalStateException-no-arg) or terse ("401"), leaving the
     * user with "unknown error" or a number. Prepending the type name keeps the failure
     * useful for filing an issue.
     */
    private fun formatException(e: Exception): String {
        val type = e::class.simpleName ?: "Exception"
        val msg = e.message?.takeIf { it.isNotBlank() } ?: return type
        return "$type: $msg"
    }

    private fun appendExecution(task: ScheduledTask, success: Boolean, message: String?): List<TaskExecutionLogEntry> {
        val entry = TaskExecutionLogEntry(
            timestampEpochMs = Clock.System.now().toEpochMilliseconds(),
            success = success,
            message = message,
        )
        return (listOf(entry) + task.recentExecutions).take(MAX_TASK_LOG_ENTRIES)
    }

    private suspend fun handleTaskFailure(task: ScheduledTask, error: String?, store: TaskStore) {
        val now = Clock.System.now()
        val failures = task.consecutiveFailures + 1
        val reason = error ?: "unknown error"
        val log = appendExecution(task, success = false, message = reason)

        if (task.cron != null) {
            val nextExecution = try {
                CronExpression(task.cron).nextAfter(now)
            } catch (_: Exception) {
                null
            }
            if (nextExecution != null) {
                store.updateTask(
                    task.copy(
                        scheduledAtEpochMs = nextExecution.toEpochMilliseconds(),
                        lastResult = "Failed at $now: $reason (next retry at $nextExecution)",
                        consecutiveFailures = failures,
                        recentExecutions = log,
                    ),
                )
            } else {
                store.updateTask(
                    task.copy(
                        status = TaskStatus.COMPLETED,
                        lastResult = "Failed at $now: $reason (no next schedule)",
                        consecutiveFailures = failures,
                        recentExecutions = log,
                    ),
                )
            }
        } else {
            val backoffMs = min(POLL_INTERVAL_MS * (1L shl min(failures, 10)), MAX_BACKOFF_MS)
            store.updateTask(
                task.copy(
                    scheduledAtEpochMs = now.toEpochMilliseconds() + backoffMs,
                    lastResult = "Failed at $now: $reason (retry after ${backoffMs / 1000}s backoff)",
                    consecutiveFailures = failures,
                    recentExecutions = log,
                ),
            )
        }
    }

    private suspend fun handleTaskCompletion(task: ScheduledTask, store: TaskStore) {
        val now = Clock.System.now()
        val log = appendExecution(task, success = true, message = null)
        if (task.cron != null) {
            val nextExecution = try {
                CronExpression(task.cron).nextAfter(now)
            } catch (e: Exception) {
                Logger.e("TaskScheduler", "failed to compute next cron time for task ${task.id}: ${e.message}")
                store.updateTask(
                    task.copy(
                        status = TaskStatus.PENDING,
                        lastResult = "Executed at $now (next schedule computation failed, will retry)",
                        consecutiveFailures = 0,
                        recentExecutions = log,
                    ),
                )
                return
            }
            if (nextExecution != null) {
                store.updateTask(
                    task.copy(
                        scheduledAtEpochMs = nextExecution.toEpochMilliseconds(),
                        lastResult = "Executed at $now",
                        status = TaskStatus.PENDING,
                        consecutiveFailures = 0,
                        recentExecutions = log,
                    ),
                )
            } else {
                store.updateTask(
                    task.copy(
                        status = TaskStatus.COMPLETED,
                        lastResult = "Executed at $now (no next schedule)",
                        consecutiveFailures = 0,
                        recentExecutions = log,
                    ),
                )
            }
        } else {
            store.updateTask(
                task.copy(
                    status = TaskStatus.COMPLETED,
                    lastResult = "Executed at $now",
                    consecutiveFailures = 0,
                    recentExecutions = log,
                ),
            )
        }
    }
}
