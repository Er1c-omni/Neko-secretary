package com.neko.secretary.overlay

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neko.secretary.live2d.CharacterStateMachine

/**
 * Quick Settings tile: pull down the shade, one tap -> companion collapses into the stealth badge
 * (0 FPS, TTS muted). Works whether or not the overlay service is currently running.
 */
class StealthTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        StealthController.restore(this)
        render()
    }

    override fun onClick() {
        super.onClick()
        StealthController.toggle(this)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val stealth = CharacterStateMachine.stealthOffice.value
        tile.state = if (stealth) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (stealth) "防社死: 已隐身" else "防社死隐身"
        tile.updateTile()
    }
}
