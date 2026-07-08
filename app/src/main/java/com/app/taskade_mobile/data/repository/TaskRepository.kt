package com.app.taskade_mobile.data.repository

import com.app.taskade_mobile.data.remote.ApiService
import com.app.taskade_mobile.task.Task
import com.app.taskade_mobile.task.TaskMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the persisted task tree via `GET /tasks` (PRD §6) and maps it to the UI
 * [Task] model. Refetched on app open and whenever a voice turn reports a
 * `tool.result` (the task list may have changed — PRD §4.2).
 */
class TaskRepository(
    private val api: ApiService
) {
    /**
     * Last task list fetched this session. Lets the Tasks screen paint immediately on
     * re-entry (instead of an empty placeholder) while a refresh runs in the background.
     */
    @Volatile
    var cachedTasks: List<Task>? = null
        private set

    suspend fun getTasks(): Result<List<Task>> = withContext(Dispatchers.IO) {
        runCatching { api.getTasks().tasks.map(TaskMapper::fromBrief) }
            .onSuccess { cachedTasks = it }
    }

    /** Permanently deletes a task (and its sub-tree) on the backend. */
    suspend fun deleteTask(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.deleteTask(id) }
            .onSuccess { cachedTasks = cachedTasks?.filterNot { it.id == id } }
            .map { }
    }
}
