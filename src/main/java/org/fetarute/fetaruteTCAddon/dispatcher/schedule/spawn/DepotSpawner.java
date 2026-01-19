package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Instant;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 负责执行“从 Depot 生成列车”的实现，可被单测替换。 */
public interface DepotSpawner {

  /**
   * 尝试从指定 depot 生成列车。
   *
   * @return 成功则返回 MinecartGroup
   */
  Optional<MinecartGroup> spawn(
      StorageProvider provider, SpawnTicket ticket, String trainName, Instant now);
}
