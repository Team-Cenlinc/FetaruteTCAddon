package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.typewriter.guardApi

@Entry("fetarute_guard_duty_fact", "玩家是否在当 FTCA 车掌", Colors.PURPLE, "mdi:bell-ring")
/**
 * [事实](/docs/creating-stories/facts)：玩家此刻是否在当车掌。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 状态 | 值 |
 * |------|----|
 * | 未值乘 | 0 |
 * | 值乘中 | 1 |
 * | 为车掌任务（被派的或从车掌任务板领的）值乘中 | 2 |
 *
 * ## 用法示例
 * 玩家值乘时隐藏某些对话选项。
 */
class OnGuardDutyFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val duty = guardApi()?.dutyOf(player.uniqueId)?.orElse(null) ?: return FactData(0)
        return FactData(if (duty.taskId().isPresent) 2 else 1)
    }
}
