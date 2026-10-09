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
import org.fetarute.typewriter.DriverStatsCache
import org.fetarute.typewriter.GuardStatsCache
import org.koin.java.KoinJavaComponent

/** 读哪一项累计车掌成绩。 */
enum class GuardStat {
    TRIPS,
    COMPLETED,
    TOTAL_POINTS,
    BEST_GRADE,
    LAST_POINTS,
}

@Entry("fetarute_guard_stat_fact", "A FetaruteTC guard record of the player", Colors.PURPLE, "mdi:medal")
/**
 * A [fact](/docs/creating-stories/facts) with one of the player's guard records.
 * Only scheduled trips on which the player did station work count.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Stat | Value |
 * |------|-------|
 * | Trips | Trips the player worked as guard |
 * | Completed | Trips worked to the end |
 * | Total points | Sum of points over trips worked to the end |
 * | Best grade | S = 5, A = 4, B = 3, C = 2, D = 1, none = 0 |
 * | Last points | Points of the last trip (0 to 100) |
 *
 * ## How could this be used?
 * Offer the driving licence quest after the player has worked ten trips as guard.
 */
class GuardStatFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Which record to read.")
    val stat: GuardStat = GuardStat.COMPLETED,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val stats = KoinJavaComponent.get<GuardStatsCache>(GuardStatsCache::class.java).of(player.uniqueId)
        val value = when (stat) {
            GuardStat.TRIPS -> stats.trips
            GuardStat.COMPLETED -> stats.completed
            GuardStat.TOTAL_POINTS -> stats.totalPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            GuardStat.BEST_GRADE -> DriverStatsCache.gradeRank(stats.bestGrade)
            GuardStat.LAST_POINTS -> stats.lastPoints
        }
        return FactData(value)
    }
}
