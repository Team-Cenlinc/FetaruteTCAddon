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
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.typewriter.AssignedTaskCache
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.driveApi
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import org.koin.java.KoinJavaComponent

@Entry("fetarute_task_state_fact", "玩家的 FTCA 驾驶任务进行到哪一步", Colors.PURPLE, "mdi:clipboard-text-clock")
/**
 * [事实](/docs/creating-stories/facts)：玩家最近的驾驶任务进行到哪一步。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 状态 | 值 |
 * |------|----|
 * | 没有任务 | 0 |
 * | 已派，等列车 | 1 |
 * | 驾驶中 | 2 |
 * | 开到终点 | 3 |
 * | 以其他方式结束（放弃、作废、未完成、中断） | -1 |
 *
 * 指定了派任务的动作时，跟着这个动作派出的那个任务走：任务结束后数值保留，玩家在终点站接着开同一列车的下一趟也不变。
 * 留空则读玩家此刻的任务（可能就是那下一趟）。
 *
 * ## 用法示例
 * 作为剧情目标的条件：“开完这一趟”在数值为 3 时完成。
 */
class TaskStateFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("只看这个动作派出的驾驶任务；留空则看玩家此刻的驾驶任务，不论来源。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val state = driveApi()?.taskOf(player.uniqueId)?.orElse(null)
            ?.takeIf { it.assignedBy(assignedBy) }
            ?.state()
            // 当前任务不是这个动作派的（例如终点站接着开的下一趟）：读它派的上一个任务怎么结束的
            ?: assignedBy.takeIf { it.isSet }?.let {
                KoinJavaComponent.get<AssignedTaskCache>(AssignedTaskCache::class.java)
                    .finishedState(player.uniqueId, it.id)
            }
            ?: return FactData(0)
        return FactData(
            when (state) {
                DriveApi.TaskState.CLAIMED -> 1
                DriveApi.TaskState.DRIVING -> 2
                DriveApi.TaskState.COMPLETED -> 3
                else -> -1
            }
        )
    }
}
