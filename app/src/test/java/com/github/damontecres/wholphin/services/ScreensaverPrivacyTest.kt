package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.components.ScreensaverItem
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.updateScreensaverPreferences
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import java.util.UUID

class ScreensaverPrivacyTest {
    @Test
    fun protected_and_missing_profiles_show_no_artwork_or_fetches() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<ServerRepository>(relaxed = true)
            val preferences = mockk<UserPreferencesService>()
            every { preferences.flow } returns emptyFlow()
            val publicUser = JellyfinUser(id = UUID.randomUUID(), serverId = UUID.randomUUID(), name = "Test", accessToken = "token")
            val service = ScreensaverService(mockk(relaxed = true), backgroundScope, mockk(relaxed = true), repository, ProfileLockService(repository), preferences, mockk(relaxed = true))
            listOf(null, publicUser.copy(pin = "1234"), publicUser.copy(requireLogin = true)).forEach { user ->
                every { repository.currentUserFlow } returns flowOf(user)
                assertEquals(if (user?.isProtected == true) ScreensaverItem.Neutral else ScreensaverItem.Empty, service.createItemFlow(backgroundScope).first())
            }
            verify(exactly = 0) { repository.jellyfin }
        } finally {
            WholphinDispatchers.reset()
        }
    }
    @Test
    fun manual_screensaver_locks_before_wake_and_wake_cannot_unlock() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<ServerRepository>()
            val server = JellyfinServer(UUID.randomUUID(), "Server", "http://localhost", "10.11.0")
            val user = JellyfinUser(id = UUID.randomUUID(), serverId = server.id, name = "Private", accessToken = "token", pin = "1234")
            every { repository.current } returns MutableStateFlow(CurrentUser(server, user))
            val preferences = mockk<UserPreferencesService>()
            every { preferences.flow } returns emptyFlow()
            val lock = ProfileLockService(repository)
            val service = ScreensaverService(mockk(relaxed = true), backgroundScope, mockk(relaxed = true), repository, lock, preferences, mockk(relaxed = true))
            service.start()
            assertTrue(service.state.value.show)
            assertNotNull(lock.locked.value)
            service.keepScreenOn(false) // Playback disposal must not wake the locked screensaver.
            runCurrent()
            assertTrue(service.state.value.show)
            service.stop(false)
            service.pulse()
            assertFalse(service.state.value.show)
            assertNotNull(lock.locked.value)
            service.stop(true) // Activity stop/restart must not dismiss the authentication gate either.
            assertNotNull(lock.locked.value)
        } finally {
            WholphinDispatchers.reset()
        }
    }

    @Test
    fun idle_timer_locks_only_protected_profiles_and_survives_preferences_reset() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<ServerRepository>()
            val server = JellyfinServer(UUID.randomUUID(), "Server", "http://localhost", "10.11.0")
            val plainUser = JellyfinUser(id = UUID.randomUUID(), serverId = server.id, name = "Public", accessToken = "token")
            val selected = MutableStateFlow<CurrentUser?>(CurrentUser(server, plainUser))
            every { repository.current } returns selected
            val preferences = mockk<UserPreferencesService>()
            val appPrefs = AppPreferences.getDefaultInstance().updateScreensaverPreferences { enabled = true; startDelay = 1000 }
            val prefs = MutableStateFlow(UserPreferences(appPrefs, null))
            every { preferences.flow } returns prefs
            coEvery { preferences.getCurrent() } answers { prefs.value }
            val lock = ProfileLockService(repository)
            val service = ScreensaverService(mockk(relaxed = true), backgroundScope, mockk(relaxed = true), repository, lock, preferences, mockk(relaxed = true))
            runCurrent()
            service.pulse()
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(service.state.value.show)
            assertNull(lock.locked.value)
            service.stop(true)
            selected.value = CurrentUser(server, plainUser.copy(requireLogin = true))
            service.pulse()
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(service.state.value.show)
            assertNotNull(lock.locked.value)
            prefs.value = prefs.value.copy(appPreferences = prefs.value.appPreferences.updateScreensaverPreferences { enabled = false })
            runCurrent()
            assertFalse(service.state.value.show)
            assertNotNull(lock.locked.value)
        } finally {
            WholphinDispatchers.reset()
        }
    }

    @Test
    fun active_playback_prevents_idle_lock_and_private_dim_only_mode_is_neutral() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<ServerRepository>()
            val server = JellyfinServer(UUID.randomUUID(), "Server", "http://localhost", "10.11.0")
            val user = JellyfinUser(id = UUID.randomUUID(), serverId = server.id, name = "Private", accessToken = "token", pin = "1234")
            every { repository.current } returns MutableStateFlow(CurrentUser(server, user))
            val preferences = mockk<UserPreferencesService>()
            val prefs = MutableStateFlow(UserPreferences(AppPreferences.getDefaultInstance().updateScreensaverPreferences { enabled = true; startDelay = 1000 }, null))
            every { preferences.flow } returns prefs
            coEvery { preferences.getCurrent() } answers { prefs.value }
            val lock = ProfileLockService(repository)
            val service = ScreensaverService(mockk(relaxed = true), backgroundScope, mockk(relaxed = true), repository, lock, preferences, mockk(relaxed = true))
            runCurrent()
            service.pulse()
            runCurrent()
            service.keepScreenOn(true)
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()
            assertFalse(service.state.value.show)
            assertNull(lock.locked.value)
            service.stop(true)
            prefs.value = prefs.value.copy(appPreferences = prefs.value.appPreferences.updateScreensaverPreferences { enabled = false; dimEnabled = true })
            runCurrent()
            service.pulse()
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(service.state.value.show)
            assertNotNull(lock.locked.value)
        } finally { WholphinDispatchers.reset() }
    }

}
