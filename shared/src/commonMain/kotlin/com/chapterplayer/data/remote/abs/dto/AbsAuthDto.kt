package com.chapterplayer.data.remote.abs.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /status: answered without signing in. */
@Serializable
data class AbsStatusDto(
    @SerialName("serverVersion") val serverVersion: String? = null,
    @SerialName("isInit") val isInit: Boolean? = null,
    // "local" means username and password sign-in is on; OpenID-only servers do not list it.
    @SerialName("authMethods") val authMethods: List<String> = emptyList()
)

@Serializable
data class AbsLoginRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
) {
    override fun toString() = "AbsLoginRequest(username=$username)"
}

/** POST /login and POST /auth/refresh. Servers before 2.26 return only [AbsUserDto.token]. */
@Serializable
data class AbsLoginResponseDto(
    @SerialName("user") val user: AbsUserDto,
    @SerialName("userDefaultLibraryId") val userDefaultLibraryId: String? = null
)

@Serializable
data class AbsUserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("type") val type: String? = null,
    /** The older, non-expiring token (deprecated; the only token on servers before 2.26). */
    @SerialName("token") val token: String? = null,
    /** Valid for an hour. */
    @SerialName("accessToken") val accessToken: String? = null,
    /** Valid for 30 days; rotates each time it is used. */
    @SerialName("refreshToken") val refreshToken: String? = null
) {
    override fun toString() = "AbsUserDto(id=$id, type=$type)"
}
