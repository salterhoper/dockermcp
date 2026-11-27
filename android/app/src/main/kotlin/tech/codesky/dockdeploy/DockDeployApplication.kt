package tech.codesky.dockdeploy

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tech.codesky.dockdeploy.data.network.ApiClient
import tech.codesky.dockdeploy.data.network.PersistentCookieJar
import tech.codesky.dockdeploy.data.preferences.AppPreferences
import tech.codesky.dockdeploy.data.repository.DockDeployRepository

class DockDeployApplication : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var cookieJar: PersistentCookieJar
        private set

    lateinit var apiClient: ApiClient
        private set

    lateinit var repository: DockDeployRepository
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        cookieJar = PersistentCookieJar(this)
        apiClient = ApiClient(cookieJar)
        repository = DockDeployRepository(apiClient)

        // Hydrate saved host & optional Bearer token synchronously on startup
        runBlocking {
            preferences.activeHostUrl.first()?.let { apiClient.setTargetHost(it) }
            preferences.apiToken.first()?.let { apiClient.apiToken = it }
        }
    }
}
