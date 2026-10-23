package io.github.tsarubucreate.audioswitcher

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/** PC の音の再生を始める・止める（画面の「再生」「停止」と、ホーム画面のウィジェットから使う）。 */
object PcPlayback {
    /** [address] の PC の音を再生する。接続先の形が正しくなければ、何もせずに false。 */
    fun start(player: Player, address: String): Boolean {
        val url = StreamAddress.toStreamUrl(address) ?: return false
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle("PC の音").setArtist(address.trim()).build())
                .build(),
        )
        player.prepare()
        player.play()
        return true
    }

    fun stop(player: Player) {
        player.pause()
        player.stop()
    }
}
