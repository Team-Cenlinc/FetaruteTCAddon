package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条可发车服务的稳定主键：以 route 为粒度。
 *
 * <p>当同一条线路配置了多条可发车 route 时，{@link StorageSpawnManager} 会按权重把 {@code Line.spawnFreqBaselineSec}
 * 分摊为各 route 的 headway，并分别生成票据。
 */
public record SpawnServiceKey(UUID routeId) {
  public SpawnServiceKey {
    Objects.requireNonNull(routeId, "routeId");
  }
}
