package com.chapterplayer.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.chapterplayer.data.PlatformFeatures

/**
 * What the platform can do, for screens that hide what it cannot. The navigation host provides it from Koin (Android:
 * everything). Outside that provider it fails closed ([DEFAULT_PLATFORM_FEATURES]: nothing), so a screen shown on its
 * own on iOS never offers a download or car control.
 */
val LocalPlatformFeatures = staticCompositionLocalOf { DEFAULT_PLATFORM_FEATURES }

internal val DEFAULT_PLATFORM_FEATURES = PlatformFeatures.None
