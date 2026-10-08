package org.fetarute.typewriter

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一名玩家的累计驾驶成绩。 */
data class DriverStats(
    val tasks: Int = 0,
    val completed: Int = 0,
    val totalPoints: Long = 0,
    val bestGrade: String = "",
    val lastPoints: Int = 0,
)

/**
 * 在线玩家累计成绩的缓存：事实条目可能在异步线程读，不能每次查库。
 *
 * 进服时从 FetaruteTCAddon 读一次，之后每开完一趟就地累加（结束事件带成绩即已写入记录）。
 */
@Singleton
class DriverStatsCache : Initializable, Listener {
    private val stats = ConcurrentHashMap<UUID, DriverStats>()

    override suspend fun initialize() {
        plugin.registerEvents(this)
        server.onlinePlayers.forEach { load(it.uniqueId) }
    }

    override suspend fun shutdown() {
        unregister()
        stats.clear()
    }

    fun of(playerId: UUID): DriverStats = stats[playerId] ?: DriverStats()

    private fun load(playerId: UUID) {
        val api = driveApi() ?: return
        api.stats(playerId).thenCombine(api.records(playerId, 1)) { total, last ->
            DriverStats(
                total.tasks(),
                total.completed(),
                total.totalPoints(),
                total.bestGrade().orElse(""),
                last.firstOrNull()?.points() ?: 0,
            )
        }.thenAccept { loaded ->
            // 读库期间玩家已经下线的不要再放回来
            if (server.getPlayer(playerId) != null) stats[playerId] = loaded
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = load(event.player.uniqueId)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        stats.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTaskFinished(event: DriverTaskFinishedEvent) {
        // 路考练习不进驾驶记录，这里也不累加，否则在线期间与库里对不上
        if (event.task.source() == TaskSource.PRACTICE) return
        val score = event.score.orElse(null) ?: return
        val completed = event.task.state().name == TaskEndState.COMPLETED.name
        stats.compute(event.playerId) { _, current ->
            val base = current ?: DriverStats()
            base.copy(
                tasks = base.tasks + 1,
                completed = base.completed + if (completed) 1 else 0,
                totalPoints = base.totalPoints + if (completed) score.points().coerceAtLeast(0) else 0,
                bestGrade = better(base.bestGrade, score.grade()),
                lastPoints = score.points(),
            )
        }
    }

    companion object {
        private const val GRADE_ORDER = "SABCD"

        /** 评级换成数：S=5 … D=1，没有评级为 0。 */
        fun gradeRank(grade: String): Int {
            val index = if (grade.isEmpty()) -1 else GRADE_ORDER.indexOf(grade)
            return if (index < 0) 0 else GRADE_ORDER.length - index
        }

        private fun better(a: String, b: String): String = if (gradeRank(b) > gradeRank(a)) b else a
    }
}
