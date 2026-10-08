package org.fetarute.typewriter

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 各派任务动作最近一次派出的任务的终态，按玩家记。
 *
 * FetaruteTCAddon 只留玩家当前的任务：终点站结算后接着开下一趟时，当前任务换成来源为 continuation 的新任务，
 * 读不到 Typewriter 派的那一趟是怎么结束的。事实条目读不到当前任务时退回这里。只在内存里，重启后清空。
 */
@Singleton
class AssignedTaskCache : Initializable, Listener {
    private val finished = ConcurrentHashMap<UUID, ConcurrentHashMap<String, DriveApi.TaskState>>()

    override suspend fun initialize() {
        plugin.registerEvents(this)
    }

    override suspend fun shutdown() {
        unregister()
        finished.clear()
    }

    /** 这个动作给玩家派的最近一个已结束任务的终态。 */
    fun finishedState(playerId: UUID, entryId: String): DriveApi.TaskState? = finished[playerId]?.get(entryId)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTaskFinished(event: DriverTaskFinishedEvent) {
        val task = event.task
        if (task.source() != SOURCE) return
        val entryId = task.metadata()[ENTRY_KEY] ?: return
        finished.computeIfAbsent(event.playerId) { ConcurrentHashMap() }[entryId] = task.state()
    }
}
