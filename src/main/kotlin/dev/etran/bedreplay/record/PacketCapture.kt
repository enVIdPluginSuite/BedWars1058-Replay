package dev.etran.bedreplay.record

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.chat.ChatTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle
import dev.etran.bedreplay.format.TitleKind
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** Receives what players are sent. Called on Netty threads, so implementations must be thread-safe. */
interface PacketSink {
    /** Cheap pre-check so packets for non-participants cost almost nothing. */
    fun wants(player: UUID): Boolean
    fun chat(player: UUID, legacyText: String)
    fun block(player: UUID, x: Int, y: Int, z: Int, state: String)
    /** [kind] is a [dev.etran.bedreplay.format.TitleKind]. */
    fun title(player: UUID, kind: Int, text: String, fadeIn: Int, stay: Int, fadeOut: Int)
    fun sound(player: UUID, name: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float)
    /** Sound attached to an entity; the session resolves the position from the entity's live id. */
    fun entitySound(player: UUID, entityId: Int, name: String, volume: Float, pitch: Float)
}

/**
 * Captures, from the outgoing packet stream, the two things events cannot give us reliably:
 *  - every chat/system line exactly as a given player saw it (kill messages, bed messages,
 *    team chat ...), which is what lets playback show "the chat of whoever you are following";
 *  - every block change sent to a player, including programmatic ones (bridge eggs, pop-up
 *    towers) that fire no Bukkit event.
 */
class PacketCapture(
    private val sink: PacketSink,
    private val log: Logger,
    private val captureChat: Boolean,
    private val captureBlocks: Boolean,
    private val captureSounds: Boolean = true,
    private val captureTitles: Boolean = true,
    private val captureScoreboards: Boolean = true,
) : PacketListenerAbstract(PacketListenerPriority.MONITOR) {

    private var reportedFailure = false

    private fun failed(t: Throwable) {
        if (!reportedFailure) {
            reportedFailure = true
            log.log(Level.WARNING, "Packet capture failed once (further failures are silent)", t)
        }
    }

    private fun isScoreboardPacket(t: com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon) =
        t == PacketType.Play.Server.SCOREBOARD_OBJECTIVE || t == PacketType.Play.Server.DISPLAY_SCOREBOARD ||
            t == PacketType.Play.Server.UPDATE_SCORE || t == PacketType.Play.Server.RESET_SCORE || t == PacketType.Play.Server.TEAMS

    override fun onPacketSend(event: PacketSendEvent) {
        val uuid = event.user?.uuid ?: return
        // Scoreboards are tracked for everybody, always: the in-game sidebar is usually sent before recording starts.
        if (captureScoreboards && isScoreboardPacket(event.packetType)) {
            try { ScoreboardRegistry.of(uuid).handle(event) } catch (t: Throwable) { failed(t) }
            return
        }
        if (!sink.wants(uuid)) return
        try {
            when (event.packetType) {
                PacketType.Play.Server.CHAT_MESSAGE -> if (captureChat) legacyChat(event, uuid)
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> if (captureChat) systemChat(event, uuid)
                PacketType.Play.Server.BLOCK_CHANGE -> if (captureBlocks) singleBlock(event, uuid)
                PacketType.Play.Server.MULTI_BLOCK_CHANGE -> if (captureBlocks) multiBlock(event, uuid)
                PacketType.Play.Server.SOUND_EFFECT, PacketType.Play.Server.NAMED_SOUND_EFFECT ->
                    if (captureSounds) sound(event, uuid)
                PacketType.Play.Server.ENTITY_SOUND_EFFECT -> if (captureSounds) entitySound(event, uuid)
                PacketType.Play.Server.TITLE -> if (captureTitles) legacyTitle(event, uuid)
                PacketType.Play.Server.SET_TITLE_TEXT ->
                    if (captureTitles) sink.title(uuid, TitleKind.TITLE, legacy(WrapperPlayServerSetTitleText(event).title), 0, 0, 0)
                PacketType.Play.Server.SET_TITLE_SUBTITLE ->
                    if (captureTitles) sink.title(uuid, TitleKind.SUBTITLE, legacy(WrapperPlayServerSetTitleSubtitle(event).subtitle), 0, 0, 0)
                PacketType.Play.Server.ACTION_BAR ->
                    if (captureTitles) sink.title(uuid, TitleKind.ACTION_BAR, legacy(WrapperPlayServerActionBar(event).actionBarText), 0, 0, 0)
                PacketType.Play.Server.SET_TITLE_TIMES -> if (captureTitles) {
                    val w = WrapperPlayServerSetTitleTimes(event)
                    sink.title(uuid, TitleKind.TIMES, "", w.fadeInTicks, w.stayTicks, w.fadeOutTicks)
                }
                PacketType.Play.Server.CLEAR_TITLES -> if (captureTitles) {
                    sink.title(uuid, if (WrapperPlayServerClearTitles(event).reset) TitleKind.RESET else TitleKind.CLEAR, "", 0, 0, 0)
                }
                else -> {}
            }
        } catch (t: Throwable) {
            if (!reportedFailure) {
                reportedFailure = true
                log.log(Level.WARNING, "Packet capture failed once (further failures are silent)", t)
            }
        }
    }

    /**
     * Pre-1.19 servers send all chat (player and system) as CHAT_MESSAGE. From 1.19 on, signed player
     * chat uses a different packet that is recorded through the chat event instead, so skip it here.
     */
    private fun legacyChat(event: PacketSendEvent, uuid: UUID) {
        if (!event.serverVersion.isOlderThan(ServerVersion.V_1_19)) return
        val msg = WrapperPlayServerChatMessage(event).message
        if (msg.type == ChatTypes.GAME_INFO) { // action bar
            if (captureTitles) sink.title(uuid, TitleKind.ACTION_BAR, legacy(msg.chatContent), 0, 0, 0)
            return
        }
        emit(uuid, AdventureSerializer.toLegacyFormat(msg.chatContent))
    }

    private fun systemChat(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerSystemChatMessage(event)
        if (w.isOverlay) { // action bar
            if (captureTitles) sink.title(uuid, TitleKind.ACTION_BAR, legacy(w.message), 0, 0, 0)
            return
        }
        emit(uuid, AdventureSerializer.toLegacyFormat(w.message))
    }

    private fun legacy(c: net.kyori.adventure.text.Component?): String = if (c == null) "" else AdventureSerializer.toLegacyFormat(c)

    /** Titles before 1.17 are one packet with an action field. */
    private fun legacyTitle(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerTitle(event)
        when (w.action) {
            WrapperPlayServerTitle.TitleAction.SET_TITLE -> sink.title(uuid, TitleKind.TITLE, legacy(w.title), 0, 0, 0)
            WrapperPlayServerTitle.TitleAction.SET_SUBTITLE -> sink.title(uuid, TitleKind.SUBTITLE, legacy(w.subtitle), 0, 0, 0)
            WrapperPlayServerTitle.TitleAction.SET_ACTION_BAR -> sink.title(uuid, TitleKind.ACTION_BAR, legacy(w.actionBar), 0, 0, 0)
            WrapperPlayServerTitle.TitleAction.SET_TIMES_AND_DISPLAY ->
                sink.title(uuid, TitleKind.TIMES, "", w.fadeInTicks, w.stayTicks, w.fadeOutTicks)
            WrapperPlayServerTitle.TitleAction.HIDE -> sink.title(uuid, TitleKind.CLEAR, "", 0, 0, 0)
            WrapperPlayServerTitle.TitleAction.RESET -> sink.title(uuid, TitleKind.RESET, "", 0, 0, 0)
            else -> {}
        }
    }

    private fun soundName(sound: com.github.retrooper.packetevents.protocol.sound.Sound?, id: Int): String? =
        try { sound?.soundId?.toString() } catch (_: Throwable) { null }

    private fun sound(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerSoundEffect(event)
        val name = soundName(w.sound, w.soundId) ?: return
        val pos = w.effectPosition // sent as fixed point, 1/8 block
        sink.sound(uuid, name, pos.x / 8.0, pos.y / 8.0, pos.z / 8.0, w.volume, w.pitch)
    }

    private fun entitySound(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerEntitySoundEffect(event)
        val name = soundName(w.sound, w.soundId) ?: return
        sink.entitySound(uuid, w.entityId, name, w.volume, w.pitch)
    }

    private fun emit(uuid: UUID, text: String) {
        if (text.isNotBlank()) sink.chat(uuid, text)
    }

    private fun singleBlock(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerBlockChange(event)
        val pos = w.blockPosition
        sink.block(uuid, pos.x, pos.y, pos.z, w.blockState.toString())
    }

    private fun multiBlock(event: PacketSendEvent, uuid: UUID) {
        val w = WrapperPlayServerMultiBlockChange(event)
        val chunk = w.chunkPosition
        // 1.16.2+ encodes absolute coordinates; older versions give offsets inside the chunk column.
        val absolute = event.serverVersion.isNewerThanOrEquals(ServerVersion.V_1_16_2)
        for (b in w.blocks) {
            val state = BlockCodec.fromGlobalId(b.blockId) ?: continue
            if (absolute) sink.block(uuid, b.x, b.y, b.z, state)
            else sink.block(uuid, chunk.x * 16 + b.x, b.y, chunk.z * 16 + b.z, state)
        }
    }
}
