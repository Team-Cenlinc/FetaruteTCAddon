package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 队列仲裁 trace 必须逐次保留，不被普通观察预算吞掉。
 *
 * <p>这条 trace 是判断“前方无车却进不去”的唯一直接证据：它同时给出双方的 priority、entryOrder 与等待时长。 实服 25 分钟日志里它只出现 4
 * 条且都不在出问题的咽喉上，导致一次持续 7 分钟的阻塞完全无法归因。
 *
 * <p>去重由<b>生产端</b>按结论签名完成（见 {@code SimpleOccupancyManager.tracePendingWinnerArbitration}），
 * 与其它免预算审计一致；诊断门只负责不丢弃。
 */
class PendingWinnerArbitrationTraceTest {

  private static final String ARBITRATION =
      "SMART_PENDING_WINNER_ARBITRATION requesterTrain=t-a requesterPriority=0"
          + " pendingWinnerTrain=t-b pendingWinnerPriority=10 resource=CONFLICT:switcher:SW-1"
          + " decision=hold-lower-priority reason=queue-blocked source=canEnter";

  @Test
  void arbitrationTraceSurvivesAnExhaustedObservationBudget() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add);

    // 先用普通观察 trace 把预算打满，再发仲裁 trace。
    for (int i = 0; i < 400; i++) {
      gate.accept("SMART_REGION_VIEW train=filler-" + i + " detail=" + i);
    }
    int beforeArbitration = out.size();

    gate.accept(ARBITRATION);

    assertTrue(out.size() > beforeArbitration, "预算耗尽后仲裁 trace 仍必须输出，否则长时间阻塞无法归因");
    assertTrue(out.get(out.size() - 1).contains("SMART_PENDING_WINNER_ARBITRATION"));
  }

  @Test
  void ordinaryObservationIsStillBudgetLimited() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add);

    for (int i = 0; i < 400; i++) {
      gate.accept("SMART_REGION_VIEW train=filler-" + i + " detail=" + i);
    }

    long emitted = out.stream().filter(line -> line.startsWith("SMART_REGION_VIEW")).count();
    assertTrue(emitted < 400, "普通观察仍须受预算约束，否则这次放行会把稳定期日志重新放大：实际输出 " + emitted);
  }
}
