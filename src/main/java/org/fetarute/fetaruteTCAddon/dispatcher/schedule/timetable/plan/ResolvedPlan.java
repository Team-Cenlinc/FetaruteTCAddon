package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.List;
import java.util.Objects;

/**
 * 时刻表编号绑定的运行计划书，解析后的结果。
 *
 * <p>库里的正文解析失败时（例如手改了库）{@code issues} 非空、{@code pattern} 为 {@link ServicePattern#NONE}：
 * 调用方应当报错而不是悄悄按没有计划书编表。
 *
 * @param planCode 计划书 code
 * @param pattern 服务模式
 * @param issues 解析问题（带页码与行号的中文描述）
 */
public record ResolvedPlan(String planCode, ServicePattern pattern, List<String> issues) {

  public ResolvedPlan {
    Objects.requireNonNull(planCode, "planCode");
    Objects.requireNonNull(pattern, "pattern");
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  /** 正文解析无误。 */
  public boolean ok() {
    return issues.isEmpty();
  }
}
