package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi
import org.fetarute.typewriter.AssignedGuardTaskCache
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry
import org.fetarute.typewriter.guardApi
import org.koin.java.KoinJavaComponent

@Entry("fetarute_guard_task_state_fact", "玩家的 FTCA 车掌任务进行到哪一步", Colors.PURPLE, "mdi:clipboard-clock")
/**
 * [事实](/docs/creating-stories/facts)：玩家最近的车掌任务进行到哪一步。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 状态 | 值 |
 * |------|----|
 * | 没有车掌任务 | 0 |
 * | 已派，等列车 | 1 |
 * | 值乘中 | 2 |
 * | 值乘到底（终点站或交班站） | 3 |
 * | 以其他方式结束（放弃、作废、未完成、中断） | -1 |
 *
 * 指定了派任务的动作时，跟着这个动作派出的那个任务走：任务结束后数值保留，玩家之后又从车掌任务板领了别的任务也不变。
 * 留空则读玩家此刻的车掌任务。
 *
 * ## 用法示例
 * 作为剧情目标的条件：“当一趟车掌”在数值为 3 时完成。
 */
class GuardTaskStateFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("只看这个动作派出的车掌任务；留空则看玩家此刻的车掌任务，不论来源。")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val state = guardApi()?.taskOf(player.uniqueId)?.orElse(null)
            ?.takeIf { it.assignedBy(assignedBy) }
            ?.state()
            // 当前任务不是这个动作派的（之后又领了别的班）：读它派的上一个任务怎么结束的
            ?: assignedBy.takeIf { it.isSet }?.let {
                KoinJavaComponent.get<AssignedGuardTaskCache>(AssignedGuardTaskCache::class.java)
                    .finishedState(player.uniqueId, it.id)
            }
            ?: return FactData(0)
        return FactData(
            when (state) {
                GuardApi.TaskState.CLAIMED -> 1
                GuardApi.TaskState.ON_DUTY -> 2
                GuardApi.TaskState.COMPLETED -> 3
                else -> -1
            }
        )
    }
}
