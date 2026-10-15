package com.chapterplayer.data.auth

/** Thrown when a server's saved sign-in no longer works and the user must sign in again. */
class SignInRequiredException(val serverName: String) : Exception("Sign in again in Settings")
