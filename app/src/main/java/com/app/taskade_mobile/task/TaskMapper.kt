package com.app.taskade_mobile.task

import com.app.taskade_mobile.data.remote.dto.TaskBrief
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Maps the backend `task_brief` (PRD §6.1) onto the UI [Task] model so the tasks
 * panel + detail screen render real data without layout changes.
 *
 * The backend has no explicit priority field, so it is derived from `status`
 * (active/blocked → HIGH, pending → MEDIUM, done/cancelled → LOW). `status` and
 * dates are formatted for display only; raw values stay on the wire model and
 * `rawStatus`.
 */
object TaskMapper {

    fun fromBrief(brief: TaskBrief): Task = Task(
        id = brief.id,
        title = brief.title,
        description = brief.description.orEmpty(),
        status = displayStatus(brief.status),
        due = displayDue(brief.dueAt),
        priority = priorityFor(brief.status),
        rawStatus = brief.status,
        taskType = displayType(brief.taskType),
        created = displayDate(brief.createdAt),
        parentTitle = brief.parentTitle
    )

    private fun displayStatus(status: String): String = when (status.lowercase(Locale.US)) {
        "pending" -> "To do"
        "active" -> "In progress"
        "blocked" -> "Blocked"
        "done" -> "Done"
        "cancelled" -> "Cancelled"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    private fun displayType(type: String?): String = when (type?.lowercase(Locale.US)) {
        "single_step" -> "Single step"
        "milestone" -> "Milestone"
        else -> type?.replaceFirstChar { it.uppercase() }.orEmpty()
    }

    private fun priorityFor(status: String): Priority = when (status.lowercase(Locale.US)) {
        "active", "blocked" -> Priority.HIGH
        "done", "cancelled" -> Priority.LOW
        else -> Priority.MEDIUM
    }

    private fun displayDue(dueAt: String?): String =
        if (dueAt.isNullOrBlank()) "No due date" else displayDate(dueAt)

    /** Formats an ISO-8601 timestamp to "MMM d, yyyy"; "" when absent/unparseable. */
    private fun displayDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val datePart = iso.substringBefore('T')
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart)
            if (parsed != null) SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed) else iso
        } catch (_: Exception) {
            iso
        }
    }
}
