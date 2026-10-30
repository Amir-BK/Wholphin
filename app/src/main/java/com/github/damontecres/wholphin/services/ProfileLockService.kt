package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps protected content out of composition until an explicit account authentication succeeds. */
@Singleton
class ProfileLockService @Inject constructor(private val repository: ServerRepository) {
    class Request(val current: CurrentUser)

    private val pending = MutableStateFlow<Request?>(null)
    val locked: StateFlow<Request?> = pending

    @Synchronized
    fun lockForScreensaver(current: CurrentUser? = repository.current.value) {
        if (current?.user?.isProtected == true && pending.value == null) {
            pending.value = Request(current)
        }
    }

    /** Only the authentication that began for this lock may unlock it. Background restores cannot. */
    @Synchronized
    fun authenticationSucceeded(request: Request?) {
        if (pending.value === request) pending.value = null
    }
}
