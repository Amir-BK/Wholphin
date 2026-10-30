package com.github.damontecres.wholphin.services

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.tvprovider.TvProviderWorker
import com.github.damontecres.wholphin.ui.successResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.robolectric.RuntimeEnvironment
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TvProviderPrivacyTest {
    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private var user: JellyfinUser? = JellyfinUser(id = userId, name = "Test", serverId = serverId, accessToken = "public-token")
    private val server = JellyfinServer(serverId, "Test", "http://localhost:8096", "10.11.0")
    private val repository = mockk<ServerRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)
    private val preferences = mockk<DataStore<AppPreferences>>()
    private val prefs = MutableStateFlow(AppPreferences.newBuilder().setCurrentServerId(serverId.toString()).setCurrentUserId(userId.toString()).build())
    private val latest = mockk<LatestNextUpService>(relaxed = true)
    private val isolatedApi = mockk<ApiClient>(relaxed = true)
    private val libraryApi = mockk<UserLibraryApi>()

    @Before
    fun setup() {
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        every { repository.serverDao.getUser(serverId, userId) } answers { user }
        every { repository.serverDao.getServer(serverId) } answers { JellyfinServerUsers(server, listOfNotNull(user)) }
        val factory = mockk<ApiClientFactory>()
        every { factory.create(any(), any(), any(), any(), any(), any()) } returns isolatedApi
        val sdk = createJellyfin {
            apiClientFactory = factory
            context = RuntimeEnvironment.getApplication()
            clientInfo = ClientInfo("PrivacyTest", "1")
            deviceInfo = DeviceInfo("PrivacyTest", "test-device")
        }
        every { repository.jellyfin } returns sdk
        every { isolatedApi.userLibraryApi } returns libraryApi
        coEvery { libraryApi.getLatestMedia(any<org.jellyfin.sdk.model.api.request.GetLatestMediaRequest>()) } returns successResponse(emptyList<BaseItemDto>())
        every { preferences.data } returns prefs
        every { params.inputData } returns Data.Builder().putString(TvProviderWorker.PARAM_SERVER_ID, serverId.toString()).putString(TvProviderWorker.PARAM_USER_ID, userId.toString()).build()
        val channels = mockk<SharedPreferences>()
        every { context.getSharedPreferences("channels", Context.MODE_PRIVATE) } returns channels
        every { channels.getString("latest", null) } returns "content://android.media.tv/channel/1"
    }

    private fun worker() = TvProviderWorker(context, params, repository, preferences, latest)

    @Test
    fun protected_profiles_clear_existing_rows_without_fetching_or_restoring() = runTest {
        listOf(user!!.copy(pin = "1234"), user!!.copy(requireLogin = true), null).forEach { protected ->
            user = protected
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
        }
        verify(exactly = 3) { resolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
        verify(exactly = 3) { resolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null) }
        coVerify(exactly = 0) { latest.getResume(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.restoreSession(any(), any()) }
        verify(exactly = 0) { resolver.bulkInsert(any(), any()) }
    }

    @Test
    fun public_profile_still_publishes_using_its_own_client() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        coVerify { latest.getResume(userId, 10, true, true, isolatedApi) }
        verify(exactly = 2) { resolver.bulkInsert(any(), any()) }
        coVerify(exactly = 0) { repository.restoreSession(any(), any()) }
    }

    @Test
    fun enabling_pin_during_fetch_discards_pending_publication() = runTest {
        coEvery { latest.getResume(any(), any(), any(), any(), any()) } coAnswers {
            user = user!!.copy(pin = "1234")
            emptyList()
        }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        verify(exactly = 0) { resolver.bulkInsert(any(), any()) }
        verify { resolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
    }

    @Test
    fun switching_profiles_during_fetch_discards_pending_publication() = runTest {
        coEvery { latest.getResume(any(), any(), any(), any(), any()) } coAnswers {
            prefs.value = prefs.value.toBuilder().setCurrentUserId(UUID.randomUUID().toString()).build()
            emptyList()
        }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        verify(exactly = 0) { resolver.bulkInsert(any(), any()) }
    }

    @Test
    fun cleanup_does_not_wait_for_an_inflight_network_request() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { latest.getResume(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            finish.await()
            emptyList()
        }
        val job = backgroundScope.launch { worker().doWork() }
        started.await()
        user = user!!.copy(requireLogin = true)
        TvProviderWorker.clearPrograms(context)
        verify { resolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
        finish.complete(Unit)
        job.join()
        verify(exactly = 0) { resolver.bulkInsert(any(), any()) }
    }

    @Test
    fun stale_queued_work_cannot_restore_a_previous_profile() = runTest {
        prefs.value = prefs.value.toBuilder().setCurrentUserId(UUID.randomUUID().toString()).build()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        coVerify(exactly = 0) { repository.restoreSession(any(), any()) }
        coVerify(exactly = 0) { latest.getResume(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { resolver.bulkInsert(any(), any()) }
    }
}


