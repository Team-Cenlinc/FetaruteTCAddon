package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Spawn 计划快照：由存储中的 Company/Operator/Line/Route 生成的“可发车服务”列表。 */
public record SpawnPlan(Instant builtAt, List<SpawnService> services) {
  public SpawnPlan {
    builtAt = builtAt == null ? Instant.EPOCH : builtAt;
    services = services == null ? List.of() : List.copyOf(services);
  }

  public static SpawnPlan empty() {
    return new SpawnPlan(Instant.EPOCH, List.of());
  }

  public int size() {
    return services.size();
  }

  public SpawnService requireService(SpawnServiceKey key) {
    Objects.requireNonNull(key, "key");
    for (SpawnService service : services) {
      if (service != null && key.equals(service.key())) {
        return service;
      }
    }
    throw new IllegalArgumentException("未找到 SpawnService: " + key);
  }
}
