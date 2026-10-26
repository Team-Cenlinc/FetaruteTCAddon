package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 控制区：运营商名下的一片站场，由若干车站与车库组成；站场图屏可以绑定一个控制区，把这几个车站连同它们之间的线路画在一张图上。将来也是信号楼的辖区。
 *
 * @param id 主键
 * @param operatorId 所属运营商
 * @param code 代码，同一运营商内唯一
 * @param name 名称
 * @param secondaryName 英文名
 * @param metadata 扩展信息
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ControlZone(
    UUID id,
    UUID operatorId,
    String code,
    String name,
    Optional<String> secondaryName,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {

  public ControlZone {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
