package com.github.damontecres.wholphin.ui.setup

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ProfileLockService
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SwitchUserWakeLockTest {
    private val server = JellyfinServer(UUID.randomUUID(), "Server", "http://localhost", "10.11.0")
    private val user = JellyfinUser(id = UUID.randomUUID(), serverId = server.id, name = "Private", accessToken = "token", pin = "1234")
    private val selected = CurrentUser(server, user)
    private val repository = mockk<ServerRepository>(relaxed = true) {
        every { current } returns MutableStateFlow(selected)
    }
    private val lock = ProfileLockService(repository)
    private val navigation = mockk<NavigationManager>(relaxed = true)
    private val setup = mockk<SetupNavigationManager>(relaxed = true)

    private fun viewModel() = SwitchUserViewModel(
        mockk(relaxed = true), mockk(relaxed = true), repository, mockk(relaxed = true),
        navigation, setup, mockk(relaxed = true), lock, server,
    )

    @Test
    fun successful_authentication_unlocks_after_navigation_is_ready() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            lock.lockForScreensaver()
            coEvery { repository.changeUser(server, user) } coAnswers {
                assertNotNull(lock.locked.value)
                selected
            }
            every { setup.navigateTo(any()) } answers { assertNotNull(lock.locked.value) }
            assertEquals(SwitchUserResult.Success, viewModel().trySwitchUser(user).await())
            assertNull(lock.locked.value)
            verify { navigation.reloadHome() }
            verify { setup.navigateTo(SetupDestination.AppContent(selected)) }
        } finally { WholphinDispatchers.reset() }
    }

    @Test
    fun authentication_error_never_reveals_application_content() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            lock.lockForScreensaver()
            coEvery { repository.changeUser(server, user) } throws IllegalStateException("denied")
            assertTrue(viewModel().trySwitchUser(user).await() is SwitchUserResult.Error)
            assertNotNull(lock.locked.value)
            verify(exactly = 0) { setup.navigateTo(any()) }
        } finally { WholphinDispatchers.reset() }
    }

    @Test
    fun screensaver_started_during_authentication_requires_another_prompt() = runTest {
        WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
        try {
            coEvery { repository.changeUser(server, user) } coAnswers {
                lock.lockForScreensaver()
                selected
            }
            assertEquals(SwitchUserResult.Success, viewModel().trySwitchUser(user).await())
            assertNotNull(lock.locked.value)
        } finally { WholphinDispatchers.reset() }
    }
}
