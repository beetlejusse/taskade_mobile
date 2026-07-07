package com.app.taskade_mobile.task

import androidx.annotation.ColorRes
import com.app.taskade_mobile.R

/** Priority maps to the palette's semantic accents: red / amber / green. */
enum class Priority(@ColorRes val colorRes: Int) {
    HIGH(R.color.danger),
    MEDIUM(R.color.warning),
    LOW(R.color.online)
}

/**
 * A single task as shown in the UI, mapped from the backend `task_brief`
 * ([TaskMapper]). [id] is the backend id used for delete + the detail screen;
 * [status]/[due]/[created] are display-formatted, while [rawStatus] keeps the
 * underlying value.
 */
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val due: String,
    val priority: Priority,
    val rawStatus: String = status,
    val taskType: String = "",
    val created: String = "",
    val parentTitle: String? = null
)
