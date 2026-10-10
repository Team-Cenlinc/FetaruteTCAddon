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
import org.fetarute.typewriter.guardApi

@Entry("fetarute_abandon_guard_duty", "Abandon the player's FetaruteTC guard duty", Colors.RED, "mdi:bell-off")
/**
 * The `Abandon Guard Duty` action ends the player's current guard duty, if Typewriter gave it.
 * A duty not yet started is dropped; a guard on duty goes off duty and the doors go back to the driver or the station.
 *
 * Guard duties the player took from the in-game guard board are left alone.
 *
 * ## How could this be used?
 * Cancel the guard duty when the player gives up the quest.
 */
class AbandonGuardDutyActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only a duty given by this action. Leave empty for any duty given by Typewriter.")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val playerId = player.uniqueId
        Dispatchers.Sync.launch {
            val api = guardApi() ?: return@launch
            // 查与放弃在主线程同一拍里做，中间不会换成别的任务
            val task = api.taskOf(playerId).orElse(null) ?: return@launch
            if (task.state().finished() || task.source() != SOURCE || !task.assignedBy(assignedBy)) {
                return@launch
            }
            api.abandon(playerId, SOURCE)
        }
    }
}
