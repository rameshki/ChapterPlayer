package com.chapterplayer.domain.jellyfin

/*
 * Jellyfin Quick Connect (J3, spec 5.1): the phone shows a short code, the person approves it on another device where they
 * are already signed in to Jellyfin, and the phone signs in without the password. These are the pure rules: how often to
 * ask, when to give up, what to do with each answer, and the words. The calls are in `JellyfinApiService`, the sign-in steps
 * in `JellyfinSignIn`, the waiting in `JellyfinSetupHandler`.
 */

/** The phone asks the server whether the code was approved this often. */
const val QUICK_CONNECT_POLL_MS = 5_000L

/** The phone gives up after this long (the server forgets an unapproved request after about 10 minutes, so the app goes first). */
const val QUICK_CONNECT_TIMEOUT_MS = 5 * 60_000L

const val QUICK_CONNECT_OFF_TEXT =
    "Quick Connect is off on this server. Sign in with your password, or ask the server's administrator to turn it on."
const val QUICK_CONNECT_EXPIRED_TEXT = "The code expired. Try again."
const val QUICK_CONNECT_NOT_APPROVED_TEXT = "Quick Connect was not approved. Try again."
const val QUICK_CONNECT_INSTRUCTIONS =
    "On a device where you are signed in to Jellyfin, open Settings, then Quick Connect, and enter this code."
const val QUICK_CONNECT_WAITING_TEXT = "Waiting for approval"
const val QUICK_CONNECT_SERVER_TROUBLE_TEXT = "Waiting for the server..."

/** What one read of the pending request said. */
enum class QuickConnectPoll {
    /** The person entered the code on another device. */
    APPROVED,

    /** Not yet. */
    WAITING,

    /** The server does not know the request any more (401 or 404): it expired or was never there. */
    UNKNOWN,

    /** The read failed (no network, a server error, an answer that is not Jellyfin's): ask again at the next tick. */
    UNREACHABLE,
}

/** What the wait does next. */
sealed interface QuickConnectStep {
    /** Approved: sign in now. */
    data object SignIn : QuickConnectStep

    /** Keep waiting; [serverTrouble] when the last read failed (the screen says so). */
    data class Wait(val serverTrouble: Boolean) : QuickConnectStep

    /** Stop: the code is no longer good. */
    data object Expired : QuickConnectStep
}

/**
 * The next step after a read: [elapsedMs] since the code was shown, [poll] what the server said. An approval signs in; a
 * request the server forgot ends the wait at once; otherwise the wait goes on until [QUICK_CONNECT_TIMEOUT_MS], also
 * through failed reads (they are tried again at the next tick).
 */
fun quickConnectNext(elapsedMs: Long, poll: QuickConnectPoll): QuickConnectStep = when {
    poll == QuickConnectPoll.APPROVED -> QuickConnectStep.SignIn
    poll == QuickConnectPoll.UNKNOWN -> QuickConnectStep.Expired
    elapsedMs >= QUICK_CONNECT_TIMEOUT_MS -> QuickConnectStep.Expired
    else -> QuickConnectStep.Wait(serverTrouble = poll == QuickConnectPoll.UNREACHABLE)
}

/** The line under the code while waiting. */
fun quickConnectWaitingText(serverTrouble: Boolean): String =
    if (serverTrouble) QUICK_CONNECT_SERVER_TROUBLE_TEXT else QUICK_CONNECT_WAITING_TEXT
