package com.koen.haremote.data

import kotlinx.serialization.Serializable

/**
 * A REST endpoint that buttons can target. Home Assistant is reached at the same base URL
 * (`baseUrl`, e.g. "http://ha.lan:8123") and the same base path (`basePath`, e.g.
 * "api/services/script") for every button, and every call needs the same bearer token - so
 * all three live here once instead of being repeated on every [ButtonConfig]. A button only
 * supplies the bit that's actually specific to it - see [ButtonConfig.path] and
 * `RestCaller.buildUrl`.
 */
@Serializable
data class HostConfig(
    val id: Int,
    val name: String = "Home Assistant",
    val baseUrl: String = "",
    val basePath: String = "",
    val bearerToken: String = ""
)

fun defaultHosts(): List<HostConfig> = listOf(HostConfig(id = 1))
