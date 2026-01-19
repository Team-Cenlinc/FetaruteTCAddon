package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 尝试将 SpawnTicket 变成真实列车或将 ticket 分配给待命列车（未来扩展）。 */
public interface TicketAssigner {

  /** 执行一次调度 tick（建议由 Bukkit task 驱动）。 */
  void tick(StorageProvider provider, Instant now);
}
