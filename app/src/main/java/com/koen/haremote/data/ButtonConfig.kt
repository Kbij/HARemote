package com.koen.haremote.data

import kotlinx.serialization.Serializable

/**
 * A single Home Assistant REST call, represented as a button on the home screen.
 * Everything here is editable by the user through the settings screen and can be
 * exported/imported as part of the JSON settings file.
 *
 * The host, base URL and bearer token are *not* repeated here - a button only points at a
 * [HostConfig] by [hostId] and supplies the bit of the URL that's specific to it ([path]);
 * see [HostConfig] and `RestCaller.buildUrl`.
 */
@Serializable
data class ButtonConfig(
    val id: Int,
    val label: String = "",
    val icon: ButtonIcon = ButtonIcon.POWER,
    val hostId: Int? = null,
    val path: String = "",
    val method: HttpMethod = HttpMethod.POST,
    val body: String = ""
)

@Serializable
enum class HttpMethod { GET, POST }

@Serializable
enum class ButtonIcon { POWER, LIGHTBULB, MOVIE, VOLUME, PLAY, TV }

fun defaultButtons(): List<ButtonConfig> = listOf(
    ButtonConfig(id = 1, label = "Film modus", icon = ButtonIcon.MOVIE),
    ButtonConfig(id = 2, label = "Lichten aan", icon = ButtonIcon.LIGHTBULB),
    ButtonConfig(id = 3, label = "Lichten uit", icon = ButtonIcon.LIGHTBULB),
    ButtonConfig(id = 4, label = "TV aan/uit", icon = ButtonIcon.TV),
    ButtonConfig(id = 5, label = "Volume", icon = ButtonIcon.VOLUME),
    ButtonConfig(id = 6, label = "Play/Pauze", icon = ButtonIcon.PLAY)
)
