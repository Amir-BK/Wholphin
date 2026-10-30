package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class WakeAuthenticationProtectionTest {
    @Test
    fun password_wake_authentication_preserves_pin_but_regular_login_keeps_existing_behavior() = runTest {
        val repository = spyk(ServerRepository(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), StandardTestDispatcher(testScheduler)))
        coEvery { repository.changeUser(any<JellyfinServer>(), any<JellyfinUser>()) } coAnswers {
            CurrentUser(firstArg(), secondArg())
        }
        val serverId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val user = JellyfinUser(id = userId, serverId = serverId, name = "Private", accessToken = "old", pin = "1234")
        val authentication = AuthenticationResult(accessToken = "renewed", serverId = serverId.toString(), user = UserDto(id = userId, name = "Private", hasPassword = true, hasConfiguredPassword = true, hasConfiguredEasyPassword = false))
        val renewed = repository.changeUser("http://localhost", authentication, user, preserveProfileProtection = true)
        assertEquals("1234", renewed.user.pin)
        assertEquals("renewed", renewed.user.accessToken)
        val normal = repository.changeUser("http://localhost", authentication, user)
        assertNull(normal.user.pin)
        val otherAccount = authentication.copy(user = UserDto(id = UUID.randomUUID(), name = "Other", hasPassword = true, hasConfiguredPassword = true, hasConfiguredEasyPassword = false))
        assertNull(repository.changeUser("http://localhost", otherAccount, user, preserveProfileProtection = true).user.pin)
    }
}

