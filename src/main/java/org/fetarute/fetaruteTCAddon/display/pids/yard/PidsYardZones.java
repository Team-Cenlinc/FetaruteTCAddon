package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.Objects;
import java.util.Optional;

/** 控制区查询：站场图屏绑了控制区时，按屏幕所属运营商与控制区代码找它的名称与成员。查内存目录，每次检查都会调用。 */
@FunctionalInterface
public interface PidsYardZones {

  /** 未接入：一律找不到。 */
  PidsYardZones NONE = (operator, zone) -> Optional.empty();

  /**
   * @param operatorCode 运营商代码
   * @param zoneCode 控制区代码
   * @return 找不到时为空
   */
  Optional<Zone> find(String operatorCode, String zoneCode);

  /**
   * 控制区。
   *
   * @param name 名称（标题）
   * @param secondaryName 英文名
   * @param scope 成员
   */
  record Zone(String name, Optional<String> secondaryName, YardScope scope) {
    public Zone {
      Objects.requireNonNull(name, "name");
      secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
      Objects.requireNonNull(scope, "scope");
    }
  }
}
