package com.app.taskade_mobile.data.remote

import com.app.taskade_mobile.data.remote.dto.DeviceTokenRequest
import com.app.taskade_mobile.data.remote.dto.GreetingResponse
import com.app.taskade_mobile.data.remote.dto.LocationRequest
import com.app.taskade_mobile.data.remote.dto.LocationResponse
import com.app.taskade_mobile.data.remote.dto.OkResponse
import com.app.taskade_mobile.data.remote.dto.OnboardingRequest
import com.app.taskade_mobile.data.remote.dto.ProfileResponse
import com.app.taskade_mobile.data.remote.dto.RemindersDueResponse
import com.app.taskade_mobile.data.remote.dto.SessionSyncRequest
import com.app.taskade_mobile.data.remote.dto.TtsRequest
import com.app.taskade_mobile.data.remote.dto.DeleteResponse
import com.app.taskade_mobile.data.remote.dto.TasksResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Retrofit description of the backend REST surface (PRD §6 / Appendix B).
 *
 * Every authenticated call carries `Authorization: Bearer <raw_id_token>`, added
 * transparently by [AuthInterceptor] — none of these methods take a token.
 */
interface ApiService {

    @GET("tasks")
    suspend fun getTasks(): TasksResponse

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): DeleteResponse

    @GET("profile")
    suspend fun getProfile(): ProfileResponse

    /**
     * Provision/refresh the user's record at login time — persists identity
     * (name/email from the token) plus device timezone/locale in one write, so
     * the DB isn't populated lazily as features get used.
     */
    @POST("auth/sync")
    suspend fun syncSession(@Body body: SessionSyncRequest): ProfileResponse

    /** Finalise first-run onboarding with the user's confirmed answers. */
    @POST("profile/onboarding")
    suspend fun completeOnboarding(@Body body: OnboardingRequest): ProfileResponse

    /** Synthesize text into a WAV clip in DIVYA's voice (scripted prompts). */
    @Streaming
    @POST("tts")
    suspend fun synthesize(@Body body: TtsRequest): ResponseBody

    @POST("profile/location")
    suspend fun setLocation(@Body body: LocationRequest): LocationResponse

    /**
     * Delivery, NOT a poll: this atomically returns due-and-unannounced reminders
     * and marks them reminded server-side (PRD §6.3). Call only when actually
     * surfacing reminders to the user.
     */
    @GET("reminders/due")
    suspend fun getDueReminders(): RemindersDueResponse

    @GET("engagement/greeting")
    suspend fun getGreeting(): GreetingResponse

    /** Register (or refresh) this device's FCM token so reminders can be pushed. */
    @POST("devices/register")
    suspend fun registerDevice(@Body body: DeviceTokenRequest): OkResponse

    /** Drop this device's token (e.g. on logout) so it stops receiving pushes. */
    @POST("devices/unregister")
    suspend fun unregisterDevice(@Body body: DeviceTokenRequest): OkResponse

    /** Acknowledge that a reminder notification actually surfaced (delivery proof). */
    @POST("reminders/{id}/delivered")
    suspend fun markReminderDelivered(@Path("id") id: String): OkResponse
}
