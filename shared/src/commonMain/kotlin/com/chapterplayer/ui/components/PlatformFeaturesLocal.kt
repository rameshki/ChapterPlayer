package com.chapterplayer.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.chapterplayer.data.PlatformFeatures

/** What the platform can do, for screens that hide what it cannot (the navigation host provides it from Koin). */
val LocalPlatformFeatures = staticCompositionLocalOf { PlatformFeatures.Full }
