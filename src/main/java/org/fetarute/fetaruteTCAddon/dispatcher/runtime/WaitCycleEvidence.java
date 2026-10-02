package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.HashSet;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 停滞等待环的复审证据：此刻挡住一列车的车，是否全在健康监控认定的等待环里。
 *
 * <p>普通停滞清理拒绝一切"在排队等前车"的车；等待环清理例外，但只在等待完全由环解释时成立：挡路的车里只要有一列不在环里， 或根本说不清在等谁（只有排队身份、没有
 * blocker），它就仍按"在等前车"处理——那条链的源头不在环上。
 */
public final class WaitCycleEvidence {

  private WaitCycleEvidence() {}

  /**
   * 清理复审口径的"还在等前车"：等待完全由等待环解释时不算（环清理的例外）；否则有 blocker 或仍在 Gate Queue 里排队就算。
   *
   * @param blockers 最近一次授权判定里挡住它的列车名
   * @param queuedWithoutBlockers 没有 blocker 时是否仍在 Gate Queue 里排队（有 blocker 时调用方不必查，传 false）
   * @param waitCycleTrains 健康监控认定的等待环成员（含它自己）；普通清理为空
   * @return 仍在等前车时为 true，清理应拒绝
   */
  public static boolean waitingOnLiveBlocker(
      Set<String> blockers, boolean queuedWithoutBlockers, Set<String> waitCycleTrains) {
    if (explainsWait(blockers, waitCycleTrains)) {
      return false;
    }
    return (blockers != null && !blockers.isEmpty()) || queuedWithoutBlockers;
  }

  /**
   * 等待是否完全由等待环解释。
   *
   * @param blockers 最近一次授权判定里挡住它的列车名
   * @param waitCycleTrains 健康监控认定的等待环成员（含它自己）；普通清理为空
   * @return 挡路的车非空且全在环里时为 true
   */
  public static boolean explainsWait(Set<String> blockers, Set<String> waitCycleTrains) {
    if (blockers == null || blockers.isEmpty() || waitCycleTrains == null) {
      return false;
    }
    Set<String> cycle = new HashSet<>();
    waitCycleTrains.forEach(name -> cycle.add(TrainNameNormalizer.normalizeKey(name)));
    return blockers.stream()
        .allMatch(name -> cycle.contains(TrainNameNormalizer.normalizeKey(name)));
  }
}
