package com.chapterplayer.data.diagnostics

import platform.UIKit.UIDevice

internal actual fun platformDescription(): String =
    "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}, ${UIDevice.currentDevice.model}"
