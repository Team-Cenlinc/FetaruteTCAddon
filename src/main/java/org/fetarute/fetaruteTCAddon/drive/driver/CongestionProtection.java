package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 拥堵保护：被扣住太久的车达到一定数量、且阻挡链里有驾驶员列车时，交还全部驾驶员列车，并在冷却期内暂停接班。本类不依赖服务器对象。 */
public final class CongestionProtection {

  /** 沿阻挡链最多追几层。 */
  private static final int MAX_CHAIN_DEPTH = 8;

  /**
   * 一列被扣住的车。
   *
   * @param held 已被扣住多久
   * @param blockers 挡住它的列车
   */
  public record Hold(Duration held, Set<String> blockers) {
    public Hold {
      held = held == null ? Duration.ZERO : held;
      blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
    }
  }

  private Instant openUntil;
  private String lastReason = "";

  /** 冷却期内（暂停接班）。 */
  public boolean open(Instant now) {
    return openUntil != null && now.isBefore(openUntil);
  }

  /** 冷却结束的时刻；不在保护中时为 {@code null}。 */
  public Instant openUntil() {
    return openUntil;
  }

  public String lastReason() {
    return lastReason;
  }

  /** 管理员解除拥堵保护。 */
  public void reset() {
    openUntil = null;
  }

  /**
   * 评估是否触发拥堵保护；触发时进入冷却期。
   *
   * @param holds 每列被扣住的车
   * @param driverTrains 驾驶员控制的列车
   * @return 这一次是否触发了拥堵保护（冷却期内不重复触发）
   */
  public boolean evaluate(
      Map<String, Hold> holds, Set<String> driverTrains, DriverRecovery recovery, Instant now) {
    if (open(now) || driverTrains.isEmpty()) {
      return false;
    }
    Duration threshold = Duration.ofSeconds(recovery.protectionHeldSeconds());
    int held = 0;
    boolean driverInChain = false;
    for (Map.Entry<String, Hold> entry : holds.entrySet()) {
      if (entry.getValue().held().compareTo(threshold) < 0) {
        continue;
      }
      held++;
      if (!driverInChain && chainReachesDriver(entry.getKey(), holds, driverTrains)) {
        driverInChain = true;
      }
    }
    if (held < recovery.protectionHeldTrains() || !driverInChain) {
      return false;
    }
    openUntil = now.plus(Duration.ofMinutes(recovery.protectionCooldownMinutes()));
    lastReason = held + " 列被扣住超过 " + recovery.protectionHeldSeconds() + " 秒，阻挡链里有驾驶员列车";
    return true;
  }

  /** 从一列被扣的车沿“谁挡住它”追下去，能否追到驾驶员列车。 */
  static boolean chainReachesDriver(
      String start, Map<String, Hold> holds, Set<String> driverTrains) {
    Set<String> visited = new HashSet<>();
    Set<String> frontier = Set.of(start);
    for (int depth = 0; depth < MAX_CHAIN_DEPTH && !frontier.isEmpty(); depth++) {
      Set<String> next = new HashSet<>();
      for (String train : frontier) {
        if (!visited.add(train)) {
          continue;
        }
        Hold hold = holds.get(train);
        if (hold == null) {
          continue;
        }
        for (String blocker : hold.blockers()) {
          if (driverTrains.contains(blocker)) {
            return true;
          }
          next.add(blocker);
        }
      }
      frontier = next;
    }
    return false;
  }
}
