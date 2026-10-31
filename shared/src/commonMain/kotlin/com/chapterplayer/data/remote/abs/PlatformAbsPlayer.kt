package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsPlayerProfile

/** This platform's player as Audiobookshelf's play request describes it ([AbsPlayerProfile.Android] or [AbsPlayerProfile.Ios]). */
internal expect val platformAbsPlayer: AbsPlayerProfile
