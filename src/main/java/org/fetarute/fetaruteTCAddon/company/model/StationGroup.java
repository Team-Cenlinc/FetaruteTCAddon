package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 车站组：乘客视角的一座换乘站。
 *
 * <p>成员是 {@link Station} 记录，可以跨运营商、跨公司（例如 FTA 的 SL 线车站与 SURC 的车站在同一处）。
 * 同一运营商、同一站码的不同股道本来就是同一站，不需要建组； 车站组只解决“不同车站记录属于同一换乘站”。一个车站最多属于一个组。
 *
 * <p>组属于创建者所在的公司（{@code companyId}），编辑需要该公司的管理权限。
 */
public record StationGroup(
    UUID id,
    UUID companyId,
    String code,
    String name,
    Optional<String> secondaryName,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public StationGroup {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
