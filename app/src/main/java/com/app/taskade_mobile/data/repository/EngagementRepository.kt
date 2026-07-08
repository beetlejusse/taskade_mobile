package com.app.taskade_mobile.data.repository

import com.app.taskade_mobile.data.remote.ApiService
import com.app.taskade_mobile.data.remote.dto.TaskBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of consuming the due-reminder delivery endpoint. */
data class DueReminders(
    val count: Int,
    val message: String?,
    val tasks: List<TaskBrief>
)

/**
 * App-open engagement: the personalized greeting (`GET /engagement/greeting`) and
 * the due-reminder delivery (`GET /reminders/due`) — PRD §6.
 *
 * [getDueReminders] is **consume-on-read**: it marks the returned reminders as
 * delivered server-side, so it must be called only when the app actually intends
 * to surface them (app open / a fired notification), never as a UI refresh poll
 * (PRD §6.3).
 */
class EngagementRepository(
    private val api: ApiService
) {
    suspend fun getGreeting(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching { api.getGreeting().greeting }
    }

    suspend fun getDueReminders(): Result<DueReminders> = withContext(Dispatchers.IO) {
        runCatching {
            val res = api.getDueReminders()
            DueReminders(count = res.count, message = res.message, tasks = res.tasks)
        }
    }
}
