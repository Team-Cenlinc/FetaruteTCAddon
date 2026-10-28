package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行计划书：挂在运营商下、按 code 引用的一份分时段频率与定点班次，正文是书的原文（见 {@link TimetablePlanBook}）。
 *
 * <p>计划书以时刻表编号为单位绑定（见 {@link TimetablePlanBinding}），不挂在 route 上：同一批 route 可以在工作日表、周末表各用一本。
 *
 * @param id 主键
 * @param operatorId 所属运营商
 * @param code 计划 code（运营商内唯一，区分大小写，与线路、route 的 code 一致）
 * @param name 显示名；未单独起名时与 code 相同
 * @param body 正文，页间以 {@link TimetablePlanBook#PAGE_BREAK} 分隔
 * @param createdAt 创建时刻
 * @param updatedAt 最后一次保存的时刻
 */
public record TimetablePlan(
    UUID id,
    UUID operatorId,
    String code,
    String name,
    String body,
    Instant createdAt,
    Instant updatedAt) {

  public TimetablePlan {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    code = code.trim();
    if (code.isEmpty()) {
      throw new IllegalArgumentException("code 不能为空");
    }
    name = name == null || name.isBlank() ? code : name.trim();
    body = body == null ? "" : body;
  }
}
