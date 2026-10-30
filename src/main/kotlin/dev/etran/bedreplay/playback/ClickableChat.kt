package dev.etran.bedreplay.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.chat.ChatTypes
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player

/** Chat lines with a click action, sent as packets so they work on every version without any chat API. */
object ClickableChat {
    /** Returns false if the packet could not be sent (the caller then falls back to plain text). */
    fun send(p: Player, legacyText: String, command: String, hover: String): Boolean = try {
        val component = AdventureSerializer.fromLegacyFormat(legacyText)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(AdventureSerializer.fromLegacyFormat(hover)))
        val pm = PacketEvents.getAPI().playerManager
        if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_19)) {
            pm.sendPacket(p, WrapperPlayServerSystemChatMessage(false, component))
        } else {
            pm.sendPacket(p, WrapperPlayServerChatMessage(ChatMessageLegacy(component, ChatTypes.CHAT)))
        }
        true
    } catch (_: Throwable) {
        false
    }
}
