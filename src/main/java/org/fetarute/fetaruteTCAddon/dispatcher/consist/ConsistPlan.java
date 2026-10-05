package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 编组方案：挂在运营商下、按名字引用的一份车型配比，正文是方案书的原文（见 {@link ConsistPlanBook}）。
 *
 * <p>route 在 metadata 的 {@code consist_plan} 里写方案名；同一份方案可以给多条 route 用。
 *
 * @param id 主键
 * @param operatorId 所属运营商
 * @param name 方案名（运营商内唯一，不区分大小写）
 * @param body 正文
 * @param createdAt 创建时刻
 * @param updatedAt 最后一次保存的时刻
 */
public record ConsistPlan(
    UUID id, UUID operatorId, String name, String body, Instant createdAt, Instant updatedAt) {

  public ConsistPlan {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    name = name.trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name 不能为空");
    }
    body = body == null ? "" : body;
  }

  /** 查找用的名字：不区分大小写。 */
  public String nameKey() {
    return nameKey(name);
  }

  /** 把方案名转成查找用的键。 */
  public static String nameKey(String name) {
    return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
  }

  /** 方案指纹：正文或保存时刻变了，按班次份额的记账就从零开始。 */
  public String fingerprint() {
    return id + "@" + updatedAt.toEpochMilli();
  }
}
