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
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyEndedEvent
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyStartEvent
import org.fetarute.typewriter.guardApi

@Entry("fetarute_guard_duty_audience", "正在当 FTCA 车掌的玩家", Colors.MEDIUM_SEA_GREEN, "mdi:bell-ring")
/**
 * “值乘中的车掌”受众：筛选正在当车掌的玩家。
 *
 * ## 用法示例
 * 只在玩家值乘时显示剧情提示。
 */
class OnGuardDutyAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    @Help("只算为车掌任务（被派的或从车掌任务板领的）值乘的玩家。")
    val withTaskOnly: Boolean = false,
    override val inverted: Boolean = false,
) : AudienceFilterEntry, Invertible {
    override suspend fun display(): AudienceFilter = OnGuardDutyAudienceFilter(ref(), withTaskOnly)
}

class OnGuardDutyAudienceFilter(
    ref: Ref<out AudienceFilterEntry>,
    private val withTaskOnly: Boolean,
) : AudienceFilter(ref) {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDutyStart(event: GuardDutyStartEvent) {
        val player = event.player.orElse(null) ?: return
        if (!canConsider(player)) return
        // 这是上岗前的事件：下一 tick 按实际值乘再判
        Dispatchers.Sync.launch {
            delay(50)
            if (player.isOnline) player.refresh()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDutyEnded(event: GuardDutyEndedEvent) {
        val player = event.player.orElse(null) ?: return
        if (!canConsider(player)) return
        player.updateFilter(false)
    }

    override fun filter(player: Player): Boolean {
        val duty = guardApi()?.dutyOf(player.uniqueId)?.orElse(null) ?: return false
        return !withTaskOnly || duty.taskId().isPresent
    }
}
