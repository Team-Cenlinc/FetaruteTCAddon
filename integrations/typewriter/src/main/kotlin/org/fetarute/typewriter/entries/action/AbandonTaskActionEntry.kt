package org.fetarute.typewriter.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.utils.Sync
import kotlinx.coroutines.Dispatchers
import org.fetarute.typewriter.SOURCE
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.driveApi

@Entry("fetarute_abandon_task", "放弃玩家的 FTCA 驾驶任务", Colors.RED, "mdi:train-variant")
/**
 * “放弃驾驶任务”动作：结束玩家由 Typewriter 派出的驾驶任务。
 * 还没接班的直接作废；正在驾驶的，列车停下后交还自动运行。
 *
 * 玩家自己从任务板领的任务不受影响。
 *
 * ## 用法示例
 * 玩家放弃剧情任务时，一并取消派给他的驾驶任务。
 */
class AbandonTaskActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只放弃这个动作派出的驾驶任务；留空则放弃任何由 Typewriter 派出的驾驶任务。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val playerId = player.uniqueId
        Dispatchers.Sync.launch {
            val api = driveApi() ?: return@launch
            // 查与放弃在主线程同一拍里做，中间不会换成别的任务
            val task = api.taskOf(playerId).orElse(null) ?: return@launch
            if (task.state().finished() || task.source() != SOURCE || !task.assignedBy(assignedBy)) {
                return@launch
            }
            api.abandon(playerId, SOURCE)
        }
    }
}
