package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.ServerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import coil3.imageLoader
import coil3.request.ImageRequest
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.components.ScreensaverItem
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles the queue of items to show on the screensaver, both in-app or OS
 */
@Singleton
class ScreensaverService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        val profileLockService: ProfileLockService,
        private val userPreferencesService: UserPreferencesService,
        private val imageUrlService: ImageUrlService,
    ) {
        private val _state = MutableStateFlow(ScreensaverState(false, false, false, false))
        val state: StateFlow<ScreensaverState> = _state

        val keepScreenOn = MutableStateFlow(false)

        private var waitJob: Job? = null
        private var dimJob: Job? = null

        init {
            userPreferencesService.flow
                .onEach { prefs ->
                    _state.update {
                        val enabled =
                            prefs.appPreferences.interfacePreferences.screensaverPreference.enabled
                        keepScreenOnInternal(enabled)
                        ScreensaverState(
                            enabled = enabled,
                            enabledTemp = false,
                            active = false,
                            paused = false,
                            dimEnabled = prefs.appPreferences.interfacePreferences.screensaverPreference.dimEnabled,
                            dimActive = false,
                        )
                    }
                }.launchIn(scope)
        }

        /**
         * Reset the timer before showing the in-app screensaver
         */
        fun pulse() {
            waitJob?.cancel()
            if (_state.value.enabled) {
//                Timber.v("pulse")
                _state.update {
                    if (!it.active) {
                        it.copy(
                            active = false,
                            dimActive = false,
                        )
                    } else {
                        it
                    }
                }

                if (!_state.value.paused) {
                    waitJob =
                        scope.launch(ExceptionHandler()) {
                            val startDelay =
                                userPreferencesService
                                    .getCurrent()
                                    .appPreferences.interfacePreferences.screensaverPreference.startDelay.milliseconds
                            delay(startDelay)
                            if (_state.value.paused) return@launch
                            profileLockService.lockForScreensaver()
                            _state.update {
                                it.copy(
                                    active = true,
                                    dimActive = it.dimEnabled,
                                )
                            }
                        }
                }
            }
            dimJob?.cancel()
            if (_state.value.dimEnabled) {
                _state.update {
                    it.copy(dimActive = false)
                }
                if (!_state.value.paused) {
                    dimJob =
                        scope.launch(ExceptionHandler()) {
                            val startDelay =
                                userPreferencesService
                                    .getCurrent()
                                    .appPreferences.interfacePreferences.screensaverPreference.startDelay.milliseconds
                            delay(startDelay)
                            Timber.v("state dim=%s", _state.value)
                            if (!_state.value.paused) {
                                profileLockService.lockForScreensaver()
                                if (profileLockService.locked.value != null) {
                                    start()
                                } else {
                                    _state.update { it.copy(dimActive = true) }
                                }
                            }
                        }
                }
            }
        }

        /**
         * Immediately start the in-app screensaver
         */
        fun start() {
            profileLockService.lockForScreensaver()
            _state.update {
                it.copy(
                    enabledTemp = true,
                    active = true,
                    paused = if (profileLockService.locked.value != null) false else it.paused,
                    dimActive = true,
                )
            }
        }

        /**
         * Immediately stop the in-app screensaver
         */
        fun stop(cancelJob: Boolean) {
            _state.update {
                it.copy(
                    enabledTemp = false,
                    active = false,
                    dimActive = false,
                )
            }
            if (cancelJob) {
                waitJob?.cancel()
                dimJob?.cancel()
            }
        }

        /**
         * Signal to the OS for keeping the screen on such as during playback or when the in-app screensaver is active
         */
        fun keepScreenOn(keep: Boolean) {
            scope.launchDefault {
                // Disposing playback when a lock begins must not dismiss the neutral saver.
                if (profileLockService.locked.value != null && state.value.active) return@launchDefault
                val screensaverEnabled = state.value.enabled
                val dimEnabled = state.value.dimEnabled
                Timber.d("Keep screen on: %s, screensaverEnabled=%s", keep, screensaverEnabled)
                if (screensaverEnabled || dimEnabled) {
                    // Page is requesting to keep screen on, so we don't wait to show the screensaver
                    _state.update {
                        it.copy(
                            active = false,
                            dimActive = false,
                            paused = keep,
                        )
                    }
                    if (!keep) {
                        pulse()
                    }
                }
                if (!screensaverEnabled) {
                    // If in-app screensaver is not enabled, send keep screen on to the OS
                    keepScreenOnInternal(keep)
                }
            }
        }

        private fun keepScreenOnInternal(keep: Boolean) {
            keepScreenOn.update { keep }
        }

        /**
         * Create a flow of items to show on the screensaver
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun createItemFlow(scope: CoroutineScope): Flow<ScreensaverItem?> =
            serverRepository.currentUserFlow.combine(profileLockService.locked) { user, request ->
                request?.current?.user ?: user
            }.flatMapLatest { user ->
                if (user == null || user.isProtected) {
                    flowOf(if (user?.isProtected == true) ScreensaverItem.Neutral else ScreensaverItem.Empty)
                } else {
                    createPublicItemFlow(scope)
                }
            }

        private fun createPublicItemFlow(scope: CoroutineScope): Flow<ScreensaverItem?> =
            flow {
                emit(ScreensaverItem.Empty)
                val current = serverRepository.current.value ?: return@flow
                if (current.user.isProtected) return@flow
                val api = serverRepository.jellyfin.createApi(baseUrl = current.server.url, accessToken = current.user.accessToken)
                val imageUrlService = ImageUrlService(api)
                val pager =
                    try {
                        createPager(api, scope)
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error creating pager for screensaver")
                        emit(ScreensaverItem.Error(ex))
                        return@flow
                    }
                Timber.v("Got %s items", pager.size)
                var index = 0
                if (pager.isEmpty()) {
                    emit(ScreensaverItem.Empty)
                } else {
                    val duration =
                        userPreferencesService
                            .getCurrent()
                            .appPreferences
                            .interfacePreferences.screensaverPreference.duration.milliseconds
                    while (true) {
                        try {
                            val item = pager.getBlocking(index)
                            Timber.v("Next index=%s, item=%s", index, item?.id)
                            if (item != null) {
                                val backdropUrl =
                                    if (item.type == BaseItemKind.PHOTO) {
                                        api.libraryApi.getDownloadUrl(item.id)
                                    } else {
                                        imageUrlService.getItemImageUrl(item, ImageType.BACKDROP)
                                    }
                                val title =
                                    if (item.type == BaseItemKind.PHOTO) {
                                        item.data.premiereDate?.let {
                                            formatDate(it.toLocalDate())
                                        }
                                    } else {
                                        item.title
                                    }
                                val logoUrl = imageUrlService.getItemImageUrl(item, ImageType.LOGO)
                                if (backdropUrl != null) {
                                    context.imageLoader
                                        .enqueue(
                                            ImageRequest
                                                .Builder(context)
                                                .data(backdropUrl)
                                                .build(),
                                        ).job
                                        .await()
                                    emit(
                                        ScreensaverItem.CurrentItem(
                                            item,
                                            backdropUrl,
                                            logoUrl,
                                            title ?: "",
                                        ),
                                    )
                                    delay(duration)
                                }
                            }
                        } catch (_: CancellationException) {
                            break
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error fetching next item")
                            delay(duration)
                        }
                        index++
                        if (index > pager.lastIndex) index = 0
                    }
                }
            }.flowOn(WholphinDispatchers.Default).cancellable()

        private suspend fun createPager(api: ApiClient, scope: CoroutineScope): ApiRequestPager<GetItemsRequest> {
            val prefs =
                userPreferencesService.flow
                    .first()
                    .appPreferences
                    .interfacePreferences.screensaverPreference
            val maxAge = prefs.maxAgeFilter.takeIf { it >= 0 }
            val itemTypes = prefs.itemTypesList.map { BaseItemKind.fromName(it) }
            val request =
                GetItemsRequest(
                    recursive = true,
                    includeItemTypes = itemTypes,
                    imageTypes = if (BaseItemKind.PHOTO in itemTypes) null else listOf(ImageType.BACKDROP),
                    sortBy = listOf(ItemSortBy.RANDOM),
                    maxOfficialRating = maxAge?.toString(),
                    hasParentalRating = maxAge?.let { true },
                )
            return ApiRequestPager(api, request, GetItemsRequestHandler, scope).init()
        }

        companion object {
            val enterAnimation = fadeIn(animationSpec = tween(durationMillis = 1000))
            val exitAnimation = fadeOut(animationSpec = tween(durationMillis = 500))
        }
    }

data class ScreensaverState(
    val enabled: Boolean,
    val enabledTemp: Boolean,
    val active: Boolean,
    val paused: Boolean,
    val dimEnabled: Boolean = false,
    val dimActive: Boolean = false,
) {
    val show get() = (enabled || enabledTemp) && active && !paused

    val showDim get() = dimEnabled && dimActive && !paused
}
