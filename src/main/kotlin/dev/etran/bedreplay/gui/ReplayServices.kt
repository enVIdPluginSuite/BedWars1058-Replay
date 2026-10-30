package dev.etran.bedreplay.gui

import dev.etran.bedreplay.playback.PlaybackController
import dev.etran.bedreplay.settings.PlayerSettingsStore
import dev.etran.bedreplay.storage.ReplayRepository

/** Shared handles the menus need; set once when the plugin enables. */
object ReplayServices {
    lateinit var controller: PlaybackController
    lateinit var settings: PlayerSettingsStore
    lateinit var repo: ReplayRepository
}
