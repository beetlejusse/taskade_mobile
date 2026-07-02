package com.app.taskade_mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the REST surface (PRD §6). Field names map 1:1 to the backend
 * JSON; unknown fields are ignored by the lenient Json configured in
 * `ApiProvider`, so additive backend changes won't break parsing.
 */

@Serializable
data class TasksResponse(
    val tasks: List<TaskBrief> = emptyList()
)

/** `task_brief` shape — PRD §6.1 / models/task.py. */
@Serializable
data class TaskBrief(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("parent_title") val parentTitle: String? = null
)

@Serializable
data class ProfileResponse(
    val profile: ProfileContext? = null
)

/** `profile` context shape — PRD §6.2 / models/user_profile.py. */
@Serializable
data class ProfileContext(
    @SerialName("display_name") val displayName: String? = null,
    val location: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
    @SerialName("daily_checkin_hour") val dailyCheckinHour: Int? = null,
    @SerialName("onboarding_complete") val onboardingComplete: Boolean = false
)

/** Body for `POST /profile/onboarding` — the user's confirmed first-run answers. */
@Serializable
data class OnboardingRequest(
    @SerialName("display_name") val displayName: String,
    val location: String? = null,
    val timezone: String? = null,
    @SerialName("daily_checkin_hour") val dailyCheckinHour: Int? = null
)

/** Body for `POST /tts` — text to synthesize in DIVYA's voice (returns WAV). */
@Serializable
data class TtsRequest(
    val text: String
)

@Serializable
data class LocationRequest(
    val location: String,
    val timezone: String? = null
)

/** Body for `POST /auth/sync` — device context the token doesn't carry. */
@Serializable
data class SessionSyncRequest(
    val timezone: String? = null,
    val locale: String? = null
)

@Serializable
data class LocationResponse(
    val ok: Boolean = false,
    val profile: ProfileContext? = null
)

/** `GET /reminders/due` — consume-on-read delivery (PRD §6.3). */
@Serializable
data class RemindersDueResponse(
    val count: Int = 0,
    val tasks: List<TaskBrief> = emptyList(),
    val message: String? = null
)

@Serializable
data class GreetingResponse(
    val greeting: String? = null
)

@Serializable
data class DeleteResponse(
    val ok: Boolean = false,
    val id: String? = null
)

/** Body for `POST /devices/register` and `POST /devices/unregister`. */
@Serializable
data class DeviceTokenRequest(
    val token: String,
    val platform: String = "android"
)

/** Generic `{ "ok": bool }` acknowledgement used by the push endpoints. */
@Serializable
data class OkResponse(
    val ok: Boolean = false,
    val removed: Int = 0
)
