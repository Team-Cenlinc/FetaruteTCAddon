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

@Entry("fetarute_guard_task_state_fact", "The state of the player's FetaruteTC guard duty", Colors.PURPLE, "mdi:clipboard-clock")
/**
 * A [fact](/docs/creating-stories/facts) with the state of the player's latest guard duty.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Status | Value |
 * |--------|-------|
 * | No guard duty | 0 |
 * | Given, waiting for the train | 1 |
 * | On duty | 2 |
 * | Worked to the end (terminus or handover station) | 3 |
 * | Ended otherwise (abandoned, expired, failed, interrupted) | -1 |
 *
 * With an assigning action set, the fact follows the duty that action gave: it keeps the value
 * after the duty ends, also when the player later takes another duty from the guard board.
 * Leave it empty to read whatever guard duty the player has now.
 *
 * ## How could this be used?
 * Use it as the criteria of a quest objective: "work the trip as guard" is done when the value is 3.
 */
class GuardTaskStateFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Only duties given by this action. Leave empty for the player's current guard duty, whatever gave it.")
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
