package com.chapterplayer.domain.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickConnectTest {
    @Test
    fun `an approval signs in whenever it comes`() {
        assertEquals(QuickConnectStep.SignIn, quickConnectNext(0, QuickConnectPoll.APPROVED))
        assertEquals(QuickConnectStep.SignIn, quickConnectNext(QUICK_CONNECT_TIMEOUT_MS - 1, QuickConnectPoll.APPROVED))
    }

    @Test
    fun `waiting goes on until five minutes`() {
        assertEquals(QuickConnectStep.Wait(serverTrouble = false), quickConnectNext(QUICK_CONNECT_POLL_MS, QuickConnectPoll.WAITING))
        assertEquals(QuickConnectStep.Wait(serverTrouble = false), quickConnectNext(QUICK_CONNECT_TIMEOUT_MS - 1, QuickConnectPoll.WAITING))
        assertEquals(QuickConnectStep.Expired, quickConnectNext(QUICK_CONNECT_TIMEOUT_MS, QuickConnectPoll.WAITING))
    }

    @Test
    fun `a request the server forgot ends the wait at once`() {
        assertEquals(QuickConnectStep.Expired, quickConnectNext(QUICK_CONNECT_POLL_MS, QuickConnectPoll.UNKNOWN))
    }

    @Test
    fun `a failed read is tried again until the time is up and says so`() {
        assertEquals(QuickConnectStep.Wait(serverTrouble = true), quickConnectNext(QUICK_CONNECT_POLL_MS * 3, QuickConnectPoll.UNREACHABLE))
        assertEquals(QuickConnectStep.Expired, quickConnectNext(QUICK_CONNECT_TIMEOUT_MS, QuickConnectPoll.UNREACHABLE))
    }

    @Test
    fun `the timings and words are the designed ones`() {
        assertEquals(5_000L, QUICK_CONNECT_POLL_MS)
        assertEquals(300_000L, QUICK_CONNECT_TIMEOUT_MS)
        assertEquals("The code expired. Try again.", QUICK_CONNECT_EXPIRED_TEXT)
        assertEquals("Waiting for approval", quickConnectWaitingText(false))
        assertEquals("Waiting for the server...", quickConnectWaitingText(true))
    }
}
