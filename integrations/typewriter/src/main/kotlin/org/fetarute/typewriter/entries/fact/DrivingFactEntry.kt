package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.typewriter.driveApi

@Entry("fetarute_driving_fact", "Whether the player is driving a FetaruteTC train", Colors.PURPLE, "mdi:steering")
/**
 * A [fact](/docs/creating-stories/facts) that tells whether the player is driving a train.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Status | Value |
 * |--------|-------|
 * | Not driving | 0 |
 * | Free driving (not a scheduled train) | 1 |
 * | Driving a scheduled train | 2 |
 *
 * ## How could this be used?
 * Hide dialogue options while the player is at the controls.
 */
class DrivingFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val session = driveApi()?.sessionOf(player.uniqueId)?.orElse(null) ?: return FactData(0)
        return FactData(if (session.dispatched()) 2 else 1)
    }
}
