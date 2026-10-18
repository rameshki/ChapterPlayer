package com.chapterplayer.data.connectivity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineRulesTest {
    @Test
    fun `a network with internet is online`() = assertTrue(isOnline(hasInternet = true))

    @Test
    fun `a network without internet is offline`() = assertFalse(isOnline(hasInternet = false))

    @Test
    fun `without a connectivity service the start value is online`() =
        assertTrue(initialOnline(managerAvailable = false, queryFailed = false, networkPresent = false, hasInternet = false))

    @Test
    fun `a failing query keeps the start value online`() =
        assertTrue(initialOnline(managerAvailable = true, queryFailed = true, networkPresent = false, hasInternet = false))

    @Test
    fun `with the service but no active network the start value is offline`() =
        assertFalse(initialOnline(managerAvailable = true, queryFailed = false, networkPresent = false, hasInternet = false))

    @Test
    fun `an active network with internet starts online even if the system has not validated it`() =
        assertTrue(initialOnline(managerAvailable = true, queryFailed = false, networkPresent = true, hasInternet = true))

    @Test
    fun `an active network without internet starts offline`() =
        assertFalse(initialOnline(managerAvailable = true, queryFailed = false, networkPresent = true, hasInternet = false))
}
