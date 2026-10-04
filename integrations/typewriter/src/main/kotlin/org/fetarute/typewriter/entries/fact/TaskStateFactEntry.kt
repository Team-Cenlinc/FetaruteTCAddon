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
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.driveApi
import org.fetarute.typewriter.entries.action.AssignTripActionEntry

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
 * ## How could this be used?
 * Use it as the criteria of a quest objective: "drive the trip" is done when the value is 3.
 */
class TaskStateFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Only tasks given by this action. Leave empty for any task.")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val task = driveApi()?.taskOf(player.uniqueId)?.orElse(null)
            ?.takeIf { it.assignedBy(assignedBy) }
            ?: return FactData(0)
        return FactData(
            when (task.state()) {
                DriveApi.TaskState.CLAIMED -> 1
                DriveApi.TaskState.DRIVING -> 2
                DriveApi.TaskState.COMPLETED -> 3
                else -> -1
            }
        )
    }
}
