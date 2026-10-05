package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 按班次份额给车型排先后：哪个车型比目标份额欠得多，就先用哪个。
 *
 * <p>每条 route 记各车型已经跑了几班（{@code a}），下一班车型 c 的赤字为 {@code w(c) × (n + 1) − a(c) × W}（{@code w}
 * 为权重、{@code W} 为权重和、{@code n} 为已跑班次），这是平滑加权轮转的赤字写法：确定、没有随机源，长期收敛到目标份额， 短窗口内也不会连着出同一种。3:1 给出 {@code
 * A A B A} 循环。
 *
 * <p>只按实际跑的车记账（{@link #record}），不按计划记：顶替、作废、出车失败都不必退账。新车出库时取排在最前、出得来的那个； 折返复用时在到站的车里挑排得最靠前的车型。
 *
 * <p>方案改了（指纹变了）就从零记。状态只在内存，重启归零。
 */
public final class ConsistSelector {

  /**
   * 方案里的一个车型。
   *
   * @param key 车型键
   * @param weight 权重
   */
  public record Weighted(String key, int weight) {
    public Weighted {
      Objects.requireNonNull(key, "key");
      if (weight <= 0) {
        throw new IllegalArgumentException("weight 必须为正数");
      }
    }
  }

  private final Map<UUID, Ledger> ledgers = new HashMap<>();

  /**
   * 按赤字从大到小排列方案里的车型；并列时按方案里的先后。
   *
   * @param routeId route
   * @param fingerprint 方案指纹；与上次不同就清零重记
   * @param plan 方案里的车型，按书里的顺序
   * @return 车型键，排在前面的先用
   */
  public synchronized List<String> rank(UUID routeId, String fingerprint, List<Weighted> plan) {
    Objects.requireNonNull(routeId, "routeId");
    if (plan == null || plan.isEmpty()) {
      return List.of();
    }
    Ledger ledger = ledger(routeId, fingerprint);
    long totalWeight = 0L;
    long trips = 0L;
    for (Weighted entry : plan) {
      totalWeight += entry.weight();
      trips += ledger.counts.getOrDefault(entry.key(), 0L);
    }
    long next = trips + 1L;
    long total = totalWeight;
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < plan.size(); i++) {
      order.add(i);
    }
    order.sort(
        Comparator.<Integer>comparingLong(
                i -> {
                  Weighted entry = plan.get(i);
                  long ran = ledger.counts.getOrDefault(entry.key(), 0L);
                  return -(entry.weight() * next - ran * total);
                })
            .thenComparingInt(i -> i));
    List<String> ranked = new ArrayList<>(plan.size());
    for (int index : order) {
      ranked.add(plan.get(index).key());
    }
    return ranked;
  }

  /**
   * 记一班：这条 route 刚由这个车型跑了一班。
   *
   * @param routeId route
   * @param fingerprint 方案指纹
   * @param key 车型键
   */
  public synchronized void record(UUID routeId, String fingerprint, String key) {
    Objects.requireNonNull(routeId, "routeId");
    if (key == null) {
      return;
    }
    ledger(routeId, fingerprint).counts.merge(key, 1L, Long::sum);
  }

  /**
   * 各车型已跑的班次。
   *
   * @param routeId route
   * @return 车型键 → 班次；没有记录时为空
   */
  public synchronized Map<String, Long> counts(UUID routeId) {
    Ledger ledger = ledgers.get(routeId);
    return ledger == null ? Map.of() : Map.copyOf(ledger.counts);
  }

  /** 清空全部记录。 */
  public synchronized void clear() {
    ledgers.clear();
  }

  private Ledger ledger(UUID routeId, String fingerprint) {
    String print = fingerprint == null ? "" : fingerprint;
    Ledger ledger = ledgers.get(routeId);
    if (ledger == null || !ledger.fingerprint.equals(print)) {
      ledger = new Ledger(print);
      ledgers.put(routeId, ledger);
    }
    return ledger;
  }

  private static final class Ledger {
    private final String fingerprint;
    private final Map<String, Long> counts = new LinkedHashMap<>();

    private Ledger(String fingerprint) {
      this.fingerprint = fingerprint;
    }
  }
}
