package org.fetarute.typewriter.entries.audience

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.AudienceFilter
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.entries.Invertible
import com.typewritermc.engine.paper.utils.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.fetarute.fetaruteTCAddon.api.event.DriveSessionEndedEvent
import org.fetarute.fetaruteTCAddon.api.event.DriveSessionStartEvent
import org.fetarute.typewriter.driveApi

@Entry("fetarute_driving_audience", "正在驾驶 FTCA 列车的玩家", Colors.MEDIUM_SEA_GREEN, "mdi:steering")
/**
 * “驾驶中的玩家”受众：筛选正在驾驶列车的玩家。
 *
 * ## 用法示例
 * 只在玩家驾驶时显示剧情提示。
 */
class DrivingAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    @Help("只算驾驶调度列车的玩家，不算自由驾驶。")
    val dispatchedOnly: Boolean = false,
    override val inverted: Boolean = false,
) : AudienceFilterEntry, Invertible {
    override suspend fun display(): AudienceFilter = DrivingAudienceFilter(ref(), dispatchedOnly)
}

class DrivingAudienceFilter(
    ref: Ref<out AudienceFilterEntry>,
    private val dispatchedOnly: Boolean,
) : AudienceFilter(ref) {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSessionStart(event: DriveSessionStartEvent) {
        val player = event.player.orElse(null) ?: return
        if (!canConsider(player)) return
        // 这是开始前的事件，会话之后仍可能开不起来：下一 tick 按实际会话再判
        Dispatchers.Sync.launch {
            delay(50)
            if (player.isOnline) player.refresh()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSessionEnded(event: DriveSessionEndedEvent) {
        val player = event.player.orElse(null) ?: return
        if (!canConsider(player)) return
        player.updateFilter(false)
    }

    override fun filter(player: Player): Boolean {
        val session = driveApi()?.sessionOf(player.uniqueId)?.orElse(null) ?: return false
        return !dispatchedOnly || session.dispatched()
    }
}
