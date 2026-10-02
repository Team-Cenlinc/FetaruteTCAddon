package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 优先级 trace 必须逐次保留，且按解析结果去重。
 *
 * <p>优先级是队列仲裁主键 {@code firstSeen − min(priority×500ms, 2min)} 的输入之一。实服日志里该 trace 只留下 12 条，
 * 且涉事列车一条都没有，导致无法判断一次阻塞来自优先级还是来自入队先后。
 *
 * <p>与其它免预算审计一致：去重在生产端（{@code DispatchPriorityResolver.traceResolved}）完成，诊断门只负责不丢弃。
 */
class PriorityResolvedTraceDedupTest {

  private static final String PRIORITY =
      "SMART_PRIORITY_RESOLVED context=periodic train=t-a priority=10"
          + " source=ROUTE_PROGRESS_UUID routeUuid=- routeCode=MT-1N";

  @Test
  void priorityTraceSurvivesAnExhaustedObservationBudget() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add);

    for (int i = 0; i < 400; i++) {
      gate.accept("SMART_REGION_VIEW train=filler-" + i + " detail=" + i);
    }
    int before = out.size();

    gate.accept(PRIORITY);

    assertTrue(out.size() > before, "预算耗尽后优先级 trace 仍必须输出");
    assertTrue(out.get(out.size() - 1).contains("SMART_PRIORITY_RESOLVED"));
  }
}
