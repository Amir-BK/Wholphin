package com.github.damontecres.wholphin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import com.github.damontecres.wholphin.ui.components.AppScreensaverContent
import com.github.damontecres.wholphin.ui.components.ScreensaverItem
import kotlin.time.Duration.Companion.seconds
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.components.AppScreensaver
import com.github.damontecres.wholphin.ui.nav.ApplicationContent
import com.github.damontecres.wholphin.ui.setup.SwitchServerContent
import com.github.damontecres.wholphin.ui.setup.SwitchUserContent
import com.github.damontecres.wholphin.ui.util.InterfaceCustomization
import com.github.damontecres.wholphin.ui.util.LocalInterfaceCustomization

@Composable
fun MainContent(
    backStack: MutableList<SetupDestination>,
    navigationManager: NavigationManager,
    userPreferences: UserPreferences,
    backdropService: BackdropService,
    screensaverService: ScreensaverService,
    modifier: Modifier = Modifier,
) {
    val preferences by rememberUpdatedState(userPreferences)
    Surface(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
        shape = RectangleShape,
    ) {
//                            val backStack = rememberNavBackStack(SetupDestination.Loading)
//                            setupNavigationManager.backStack = backStack
        val interfaceCustomization =
            remember(userPreferences.appPreferences) { InterfaceCustomization(userPreferences.appPreferences) }
        CompositionLocalProvider(LocalInterfaceCustomization provides interfaceCustomization) {
            val lock by screensaverService.profileLockService.locked.collectAsState()
            val screenSaverState by screensaverService.state.collectAsState()
            if (lock != null) {
                val request = lock!!
                // Remove the protected navigation tree, including video surfaces and playback VMs.
                LaunchedEffect(request) {
                    backdropService.clearBackdrop()
                    navigationManager.reloadHome()
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (screenSaverState.show) {
                        AppScreensaverContent(ScreensaverItem.Neutral, true, 30.seconds, true, Modifier.fillMaxSize())
                    } else {
                        key(request) {
                            NavDisplay(
                                backStack = listOf(SetupDestination.UserList(request.current.server)),
                                onBack = {},
                                entryDecorators = listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator(),
                                ),
                                entryProvider = { destination ->
                                    NavEntry(destination) {
                                        SwitchUserContent(
                                            server = request.current.server,
                                            modifier = Modifier.fillMaxSize(),
                                            promptUser = request.current.user,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider = { key ->
                        key as SetupDestination
                        NavEntry(key) {
                            when (key) {
                                SetupDestination.Loading -> {
                                    Box(
                                        modifier = Modifier.size(200.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.border,
                                            modifier = Modifier.align(Alignment.Center),
                                        )
                                    }
                                }

                                SetupDestination.ServerList -> {
                                    SwitchServerContent(Modifier.fillMaxSize())
                                }

                                is SetupDestination.UserList -> {
                                    SwitchUserContent(
                                        server = key.server,
                                        Modifier.fillMaxSize(),
                                    )
                                }

                                is SetupDestination.AppContent -> {
                                    LaunchedEffect(Unit) {
                                        backdropService.clearBackdrop()
                                    }
                                    val current = key.current
                                    var showContent by remember {
                                        mutableStateOf(true)
                                    }
                                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                                        if (!userPreferences.appPreferences.signInAutomatically) {
                                            showContent = false
                                        }
                                    }

                                    if (showContent) {
                                        ApplicationContent(
                                            user = current.user,
                                            server = current.server,
                                            navigationManager = navigationManager,
                                            preferences = preferences,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(200.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                color = MaterialTheme.colorScheme.border,
                                                modifier = Modifier.align(Alignment.Center),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
            if (lock == null && (screenSaverState.enabled || screenSaverState.enabledTemp)) {
                AnimatedVisibility(
                    visible = screenSaverState.show,
                    enter = ScreensaverService.enterAnimation,
                    exit = ScreensaverService.exitAnimation,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppScreensaver(userPreferences.appPreferences, Modifier.fillMaxSize())
                }
            }
            AnimatedVisibility(
                visible = lock == null && (screenSaverState.showDim || (screenSaverState.dimEnabled && screenSaverState.show)),
                enter = ScreensaverService.enterAnimation,
                exit = ScreensaverService.exitAnimation,
                modifier = Modifier.fillMaxSize(),
            ) {
                val alpha =
                    userPreferences.appPreferences.interfacePreferences.screensaverPreference.dimPercent / 100f
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = alpha)),
                )
            }
        }
    }
}
