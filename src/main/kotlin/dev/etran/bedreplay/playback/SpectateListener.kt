package dev.etran.bedreplay.playback

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Replay entities only exist on the viewer's client, so every click on one arrives as a raw packet.
 * - A click on the viewer's own entity (the camera sits at the followed entity, so the viewer's body is in the way)
 *   would make the server kick them with "Cannot interact with self!"; those packets are dropped.
 * - A punch on a replay entity with an empty hand starts spectating it.
 */
class SpectateListener(
    private val plugin: Plugin,
    private val controller: PlaybackController,
) : PacketListenerAbstract(PacketListenerPriority.LOW) {

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.INTERACT_ENTITY) return
        try {
            val uuid = event.user?.uuid ?: return
            if (!controller.isWatchingId(uuid)) return
            val wrapper = WrapperPlayClientInteractEntity(event)
            val target = wrapper.entityId
            // Viewers never interact with real entities in the replay world; swallow everything.
            event.isCancelled = true
            if (wrapper.action != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val p = Bukkit.getPlayer(uuid) ?: return@Runnable
                if (target == p.entityId) return@Runnable
                controller.punch(p, target)
            })
        } catch (_: Throwable) {
        }
    }
}
