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

@Entry("fetarute_task_state_fact", "The state of the player's FetaruteTC driving task", Colors.PURPLE, "mdi:clipboard-text-clock")
/**
 * A [fact](/docs/creating-stories/facts) with the state of the player's latest driving task.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Status | Value |
 * |--------|-------|
 * | No task | 0 |
 * | Assigned, waiting for the train | 1 |
 * | Driving | 2 |
 * | Driven to the end | 3 |
 * | Ended otherwise (abandoned, expired, failed, interrupted) | -1 |
 *
 * With an assigning action set, the fact follows the task that action gave: it keeps the value
 * after the task ends, also when the player drives on with the next trip of the same train at the terminus.
 * Leave it empty to read whatever task the player has now, which can be that next trip.
 *
 * ## How could this be used?
 * Use it as the criteria of a quest objective: "drive the trip" is done when the value is 3.
 */
class TaskStateFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Only tasks given by this action. Leave empty for the player's current task, whatever gave it.")
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
