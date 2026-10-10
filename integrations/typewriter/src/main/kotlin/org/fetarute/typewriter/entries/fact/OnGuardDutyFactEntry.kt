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

@Entry("fetarute_guard_duty_fact", "Whether the player is on duty as a FetaruteTC guard", Colors.PURPLE, "mdi:bell-ring")
/**
 * A [fact](/docs/creating-stories/facts) that tells whether the player is the guard of a train right now.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Status | Value |
 * |--------|-------|
 * | Not on duty | 0 |
 * | On duty | 1 |
 * | On duty for a guard duty they were given or took from the guard board | 2 |
 *
 * ## How could this be used?
 * Hide dialogue options while the player is working as a guard.
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
