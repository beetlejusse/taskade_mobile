package com.app.taskade_mobile.task

import androidx.annotation.ColorRes
import com.app.taskade_mobile.R

/** Priority maps to one of the existing palette accents (no new colors). */
enum class Priority(@ColorRes val colorRes: Int) {
    HIGH(R.color.danger),
    MEDIUM(R.color.muted),
    LOW(R.color.online)
}

/**
 * A single task. Static mock data for the prototype; a real source will replace
 * [mockTasks] once task fetching is wired up.
 */
data class Task(
    val title: String,
    val description: String,
    val status: String,
    val due: String,
    val priority: Priority
) {
    companion object {
        fun mockTasks(): List<Task> = listOf(
            Task("Design review", "Finalize the DIVYA chat UI polish", "In progress", "Today · 5 PM", Priority.HIGH),
            Task("Weekly planning", "Group tasks by priority and deadline", "To do", "Tomorrow", Priority.MEDIUM),
            Task("Voice modal", "Wire the animated waveform input", "To do", "Jun 24", Priority.MEDIUM),
            Task("Auth hardening", "Review Google native sign-in flow", "Done", "Jun 18", Priority.LOW),
            Task("Dock blur QA", "Verify glassmorphism on API 24–30", "In progress", "Jun 22", Priority.HIGH),
            Task("Onboarding copy", "Draft welcome and empty states", "To do", "Jun 26", Priority.LOW)
        )
    }
}
