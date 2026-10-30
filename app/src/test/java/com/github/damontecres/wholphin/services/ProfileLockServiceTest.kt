package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProfileLockServiceTest {
    private val server = JellyfinServer(UUID.randomUUID(), "Server", "http://localhost", "10.11.0")
    private val user = JellyfinUser(id = UUID.randomUUID(), serverId = server.id, name = "User", accessToken = "token")
    private val current = MutableStateFlow<CurrentUser?>(null)
    private val repository = mockk<ServerRepository> { every { current } returns this@ProfileLockServiceTest.current }
    private val service = ProfileLockService(repository)

    @Test
    fun public_and_missing_profiles_are_not_locked() {
        service.lockForScreensaver()
        assertNull(service.locked.value)
        current.value = CurrentUser(server, user)
        service.lockForScreensaver()
        assertNull(service.locked.value)
    }

    @Test
    fun both_pin_and_password_profiles_require_authentication() {
        listOf(user.copy(pin = "1234"), user.copy(requireLogin = true)).forEach { protected ->
            current.value = CurrentUser(server, protected)
            service.lockForScreensaver()
            val request = service.locked.value
            assertNotNull(request)
            assertEquals(protected.id, request!!.current.user.id)
            // Background restores, losing the session, or selecting an ordinary session do not unlock.
            current.value = null
            service.lockForScreensaver()
            assertSame(request, service.locked.value)
            current.value = CurrentUser(server, user)
            service.lockForScreensaver()
            assertSame(request, service.locked.value)
            service.authenticationSucceeded(request)
            assertNull(service.locked.value)
        }
    }

    @Test
    fun authentication_started_before_lock_cannot_unlock_it() {
        val oldAttempt = service.locked.value
        current.value = CurrentUser(server, user.copy(pin = "1234"))
        service.lockForScreensaver()
        service.authenticationSucceeded(oldAttempt)
        assertNotNull(service.locked.value)
    }

    @Test
    fun repeated_screensaver_and_stale_authentication_do_not_bypass_a_new_lock() {
        current.value = CurrentUser(server, user.copy(pin = "1234"))
        service.lockForScreensaver()
        val first = service.locked.value
        service.lockForScreensaver()
        assertSame(first, service.locked.value)
        service.authenticationSucceeded(first)
        assertNull(service.locked.value)
        service.lockForScreensaver()
        val second = service.locked.value
        assertNotSame(first, second)
        service.authenticationSucceeded(first)
        assertSame(second, service.locked.value)
        service.authenticationSucceeded(second)
        assertNull(service.locked.value)
    }
}
