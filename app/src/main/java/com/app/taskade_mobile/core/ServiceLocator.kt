package com.app.taskade_mobile.core

import android.content.Context
import com.app.taskade_mobile.data.remote.ApiProvider
import com.app.taskade_mobile.data.repository.DeviceTokenRepository
import com.app.taskade_mobile.data.repository.EngagementRepository
import com.app.taskade_mobile.data.repository.ProfileRepository
import com.app.taskade_mobile.data.repository.TaskRepository
import com.app.taskade_mobile.core.BackendHostManager

/**
 * Minimal process-wide dependency container — deliberately lightweight (no DI
 * framework) to fit the existing app. Holds the singletons every screen and the
 * voice service share: the token provider, the networking stack, and the REST
 * repositories.
 *
 * Call [init] once (lazily from any entry point); subsequent access is via the
 * member properties. Thread-safe double-checked initialization.
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    lateinit var tokenProvider: SessionTokenProvider
        private set
    lateinit var backendHostManager: BackendHostManager
        private set
    lateinit var apiProvider: ApiProvider
        private set
    lateinit var taskRepository: TaskRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var engagementRepository: EngagementRepository
        private set
    lateinit var deviceTokenRepository: DeviceTokenRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            tokenProvider = Auth0TokenProvider(appContext)
            // Failover brain: shared by REST + the voice socket. start() kicks the
            // parallel /health startup race and the background recovery prober.
            backendHostManager = BackendHostManager(ApiConfig.hosts).also { it.start() }
            apiProvider = ApiProvider(tokenProvider, backendHostManager)
            taskRepository = TaskRepository(apiProvider.apiService)
            profileRepository = ProfileRepository(apiProvider.apiService)
            engagementRepository = EngagementRepository(apiProvider.apiService)
            deviceTokenRepository = DeviceTokenRepository(apiProvider.apiService)
            initialized = true
        }
    }

    /** Convenience that ensures [init] ran, then returns the container. */
    fun get(context: Context): ServiceLocator {
        init(context)
        return this
    }
}
