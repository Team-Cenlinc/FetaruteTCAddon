package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;

/**
 * 编表用的车型：每个车型一套走行模型与车长，以及各 route 允许哪些车型、按什么份额。
 *
 * <p>没有任何车型（{@link #none()}）时编表与从前逐字相同：一个走行模型、duty 不带车型。 只要有一个车型，编表就进入"车型跟着车走"的模式：
 *
 * <ul>
 *   <li>每条 route 对它允许的每个车型各算一份时分，以派生出的<b>变体 route</b>（{@link #variantId}）登记； 派车之后每一班改挂它那辆车的车型对应的变体，
 *       下游的端点串行、让车、冲突检查按变体的时分与足迹计算；
 *   <li>route 本身（基础 route）的时分取允许车型里最慢的那一份：相位锚定、网格可行性都按它，快车型在端点多停一会， 车辆周转不会因为慢车型赶不上锚定的那一班而断开；
 *   <li>车尾出清取差额：每个车型只多算比最短车型长出来的那一截（{@link #tailBlocks}）。
 * </ul>
 *
 * @param consists 车型键 → 车型，按键排序
 * @param planByRoute route → 绑定方案里的车型与权重；没绑方案的 route 不在其中
 * @param plainRoutes 不区分车型的 route：联编时没有任何编组方案的线路，运行时按车库牌子出车，编表按基础走行模型、交路不带车型
 * @param blockedRoutes 绑了方案、但方案里没有一个车型可用的 route → 原因；这些 route 不能编表（运行时同样出不了车）
 * @param issues 方案里个别车型不可用等提示：这些车型不参与编表，写进编表警告
 */
public record ConsistFleet(
    Map<String, Consist> consists,
    Map<UUID, List<Share>> planByRoute,
    Set<UUID> plainRoutes,
    Map<UUID, String> blockedRoutes,
    List<String> issues) {

  /**
   * 一个车型。
   *
   * @param key 车型键（归一后的编组写法）
   * @param pattern 编组写法（报告里显示）
   * @param model 这个车型的走行模型
   * @param lengthBlocks 车长（格）
   * @param spawnLimit 存车的出车上限（TrainCarts {@code spawnlimit}）；不限时为空。报告拿峰值同时在线比它
   */
  public record Consist(
      String key,
      String pattern,
      RunTimeModel model,
      double lengthBlocks,
      java.util.OptionalInt spawnLimit) {

    /** 没有出车上限的车型。 */
    public Consist(String key, String pattern, RunTimeModel model, double lengthBlocks) {
      this(key, pattern, model, lengthBlocks, java.util.OptionalInt.empty());
    }

    public Consist {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(model, "model");
      pattern = pattern == null || pattern.isBlank() ? key : pattern;
      lengthBlocks = Double.isFinite(lengthBlocks) && lengthBlocks > 0.0 ? lengthBlocks : 0.0;
      spawnLimit = spawnLimit == null ? java.util.OptionalInt.empty() : spawnLimit;
    }
  }

  /**
   * route 允许的一个车型及其目标份额权重。
   *
   * @param key 车型键
   * @param weight 权重（正整数）
   */
  public record Share(String key, int weight) {
    public Share {
      Objects.requireNonNull(key, "key");
      if (weight <= 0) {
        throw new IllegalArgumentException("weight 必须为正数");
      }
    }
  }

  /** 只有车型与方案的车型表。 */
  public ConsistFleet(Map<String, Consist> consists, Map<UUID, List<Share>> planByRoute) {
    this(consists, planByRoute, Set.of(), Map.of(), List.of());
  }

  public ConsistFleet {
    Map<String, Consist> known = consists == null ? Map.of() : Map.copyOf(consists);
    consists = known;
    Map<UUID, String> blocked = new TreeMap<>();
    if (blockedRoutes != null) {
      blockedRoutes.forEach(
          (routeId, reason) -> {
            if (routeId != null) {
              blocked.put(routeId, reason == null || reason.isBlank() ? "编组方案里没有可用的车型" : reason);
            }
          });
    }
    Map<UUID, List<Share>> plans = new TreeMap<>();
    if (planByRoute != null) {
      for (Map.Entry<UUID, List<Share>> entry : planByRoute.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          continue;
        }
        List<Share> usable = new ArrayList<>();
        for (Share share : entry.getValue()) {
          if (share != null && known.containsKey(share.key())) {
            usable.add(share);
          }
        }
        if (usable.isEmpty()) {
          // 绑了方案却一个车型都用不上：不能退回"不限车型"，那会让方案之外的车型跑这条 route。
          blocked.putIfAbsent(entry.getKey(), "编组方案里没有可用的车型");
        } else {
          plans.put(entry.getKey(), List.copyOf(usable));
        }
      }
    }
    plans.keySet().removeAll(blocked.keySet());
    planByRoute = Map.copyOf(plans);
    blockedRoutes = Map.copyOf(blocked);
    Set<UUID> plain = new TreeSet<>();
    if (plainRoutes != null) {
      for (UUID routeId : plainRoutes) {
        if (routeId != null && !plans.containsKey(routeId) && !blocked.containsKey(routeId)) {
          plain.add(routeId);
        }
      }
    }
    plainRoutes = Set.copyOf(plain);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  /** 车型键，按字典序：没绑方案的 route 用它等权排列，结果不随 Map 的遍历顺序变。 */
  public List<String> keys() {
    return List.copyOf(new TreeMap<>(consists).keySet());
  }

  /** 不区分车型：与从前完全相同的编表。 */
  public static ConsistFleet none() {
    return new ConsistFleet(Map.of(), Map.of(), Set.of(), Map.of(), List.of());
  }

  /** 有没有车型。 */
  public boolean active() {
    return !consists.isEmpty();
  }

  /**
   * route 允许的车型与权重。
   *
   * <p>绑了方案取方案；没绑方案的 route 不限车型，全部车型等权——它的班次由谁来跑，看周转到这里的车。 没有车型、不区分车型的 route（{@link
   * #plainRoutes}）与不能编表的 route（{@link #blockedRoutes}）为空。
   *
   * @param routeId route（基础 route）
   * @return 车型与权重，按方案里的顺序；没绑方案时按车型键排序
   */
  public List<Share> sharesFor(UUID routeId) {
    if (!active() || plainRoutes.contains(routeId) || blockedRoutes.containsKey(routeId)) {
      return List.of();
    }
    List<Share> planned = planByRoute.get(routeId);
    if (planned != null) {
      return planned;
    }
    List<Share> all = new ArrayList<>(consists.size());
    for (String key : keys()) {
      all.add(new Share(key, 1));
    }
    return List.copyOf(all);
  }

  /** 绑了方案却没有可用车型的 route 为什么不能编表；能编表时为空。 */
  public Optional<String> blockedReason(UUID routeId) {
    return Optional.ofNullable(blockedRoutes.get(routeId));
  }

  /** 最短车型的车长；没有车型时为 0。 */
  public double minLengthBlocks() {
    return consists.values().stream().mapToDouble(Consist::lengthBlocks).min().orElse(0.0);
  }

  /**
   * 车尾出清多算的长度：比最短车型长出来的那一截。只跑一种车型（或车长都相同）时为 0，表与从前逐字相同。
   *
   * @param key 车型键
   * @return 多算的长度（格），不小于 0
   */
  public double tailBlocks(String key) {
    Consist consist = consists.get(key);
    return consist == null ? 0.0 : Math.max(0.0, consist.lengthBlocks() - minLengthBlocks());
  }

  /**
   * 变体 route 的 ID：同一条 route 按某个车型跑时的时分登记在它名下。由 route 与车型键派生，确定。
   *
   * @param routeId 基础 route
   * @param key 车型键
   * @return 变体 ID
   */
  public static UUID variantId(UUID routeId, String key) {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(key, "key");
    return UUID.nameUUIDFromBytes(
        ("consist-variant:" + routeId + ":" + key).getBytes(StandardCharsets.UTF_8));
  }
}
