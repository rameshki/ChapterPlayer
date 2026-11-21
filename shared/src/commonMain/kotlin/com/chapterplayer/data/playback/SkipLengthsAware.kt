package com.chapterplayer.data.playback

import kotlinx.coroutines.flow.Flow

/**
 * A player that follows the person's skip lengths (Settings, Playback; review R26): the pair is back and forward in
 * seconds, delivered now and again whenever they change. Android's service reads the setting itself.
 */
interface SkipLengthsAware {
    fun followSkipLengths(lengths: Flow<Pair<Int, Int>>)
}
