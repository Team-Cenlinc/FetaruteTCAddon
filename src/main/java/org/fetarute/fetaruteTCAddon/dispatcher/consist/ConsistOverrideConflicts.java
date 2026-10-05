package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 同一个编组写法在不同方案里的覆盖项必须一致。
 *
 * <p>覆盖项描述的是车本身：一辆按 A 方案出库、写着 metro 标签的车，复用到绑定 B 方案（写着 emu）的 route 上，控车、ETA 与编表就会按两套参数走。
 * 车会跨运营商流动（直通运转借外方的出库线路出车），所以检查范围是整个公司。
 */
public final class ConsistOverrideConflicts {

  private ConsistOverrideConflicts() {}

  /**
   * 一处冲突。
   *
   * @param pattern 本方案里的编组写法
   * @param otherPlan 写法相同、覆盖项不同的另一份方案名
   */
  public record Conflict(String pattern, String otherPlan) {
    public Conflict {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(otherPlan, "otherPlan");
    }
  }

  /**
   * 找出与其它方案冲突的车型。
   *
   * @param entries 要保存的方案里的车型
   * @param others 其它方案：方案名 → 车型（不含要保存的这一份）
   * @return 冲突，按其它方案名、再按书里的顺序
   */
  public static List<Conflict> find(
      List<ConsistPlanBook.Entry> entries, Map<String, List<ConsistPlanBook.Entry>> others) {
    List<Conflict> conflicts = new ArrayList<>();
    if (entries == null || entries.isEmpty() || others == null || others.isEmpty()) {
      return conflicts;
    }
    Map<String, List<ConsistPlanBook.Entry>> sorted = new TreeMap<>(others);
    for (Map.Entry<String, List<ConsistPlanBook.Entry>> other : sorted.entrySet()) {
      for (ConsistPlanBook.Entry mine : entries) {
        for (ConsistPlanBook.Entry theirs : other.getValue()) {
          if (theirs.key().equals(mine.key()) && !theirs.overrides().equals(mine.overrides())) {
            conflicts.add(new Conflict(mine.pattern(), other.getKey()));
          }
        }
      }
    }
    return conflicts;
  }
}
