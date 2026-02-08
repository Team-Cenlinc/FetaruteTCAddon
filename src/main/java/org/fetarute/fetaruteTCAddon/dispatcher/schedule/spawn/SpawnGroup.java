package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Locale;
import java.util.Optional;

/**
 * 显式交路组配置。
 *
 * <p>交路组在线路维度管理（Line.metadata），Route 通过 {@code spawn_group} 引用组名。
 */
public record SpawnGroup(String name, Optional<Integer> baselineSeconds) {

  public SpawnGroup {
    name = name == null ? "" : name.trim();
    baselineSeconds =
        baselineSeconds == null
            ? Optional.empty()
            : baselineSeconds.filter(value -> value != null && value > 0);
  }

  /** 返回用于匹配的标准化组名（小写）。 */
  public String normalizedName() {
    return name.toLowerCase(Locale.ROOT);
  }
}
