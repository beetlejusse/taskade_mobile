package com.app.taskade_mobile.data.repository

import com.app.taskade_mobile.data.remote.ApiService
import com.app.taskade_mobile.data.remote.dto.LocationRequest
import com.app.taskade_mobile.data.remote.dto.OnboardingRequest
import com.app.taskade_mobile.data.remote.dto.ProfileContext
import com.app.taskade_mobile.data.remote.dto.SessionSyncRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Profile context (`GET /profile`) and one-time location persistence
 * (`POST /profile/location`) — PRD §6 / §7.
 */
class ProfileRepository(
    private val api: ApiService
) {
    /**
     * Last profile seen this session — updated by every call that returns one. Lets
     * screens paint the known name/location instantly (and skip a redundant fetch)
     * while a refresh runs. Null until the first successful load.
     */
    @Volatile
    var cached: ProfileContext? = null
        private set

    suspend fun getProfile(): Result<ProfileContext?> = withContext(Dispatchers.IO) {
        runCatching { api.getProfile().profile }.onSuccess { it?.let { p -> cached = p } }
    }

    suspend fun setLocation(location: String, timezone: String? = null): Result<ProfileContext?> =
        withContext(Dispatchers.IO) {
            runCatching { api.setLocation(LocationRequest(location, timezone)).profile }
                .onSuccess { it?.let { p -> cached = p } }
        }

    /**
     * Provision/refresh the user's record on the backend at login time. Sends the
     * device timezone + locale (the token carries name/email server-side). Called
     * on the post-auth landing, so it also heals already-signed-in users.
     */
    suspend fun syncSession(timezone: String? = null, locale: String? = null): Result<ProfileContext?> =
        withContext(Dispatchers.IO) {
            runCatching { api.syncSession(SessionSyncRequest(timezone, locale)).profile }
                .onSuccess { it?.let { p -> cached = p } }
        }

    /** Read the profile purely to learn the onboarding gate (and prefilled name). */
    suspend fun fetchProfile(): Result<ProfileContext?> = getProfile()

    /** Submit the user's confirmed onboarding answers (name authoritative). */
    suspend fun completeOnboarding(
        displayName: String,
        location: String? = null,
        timezone: String? = null,
        dailyCheckinHour: Int? = null,
    ): Result<ProfileContext?> = withContext(Dispatchers.IO) {
        runCatching {
            api.completeOnboarding(
                OnboardingRequest(
                    displayName = displayName,
                    location = location,
                    timezone = timezone,
                    dailyCheckinHour = dailyCheckinHour,
                )
            ).profile
        }.onSuccess { it?.let { p -> cached = p } }
    }
}
