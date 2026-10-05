package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;

/**
 * 发车时的车型裁决：出什么车、到站的车里接哪辆、车跑完一班怎么记账。
 *
 * <p>route 绑了编组方案才生效；没绑的 route 一切按旧规则（出车读 route 的 {@code spawn_train_pattern}，再兜底车库牌子第 4 行；复用不看车型）。
 * 默认实现 {@link #NONE} 对所有 route 都按旧规则；票上指定了车型的出不了车。
 */
public interface ConsistArbiter {

  /** 不做任何车型裁决。 */
  ConsistArbiter NONE =
      new ConsistArbiter() {
        @Override
        public List<LayoverRegistry.LayoverCandidate> orderReuseCandidates(
            SpawnTicket ticket, List<LayoverRegistry.LayoverCandidate> candidates) {
          return candidates;
        }

        @Override
        public SpawnChoice chooseSpawn(SpawnTicket ticket) {
          // 票上指定了车型却没有编组方案可查：出不了那个车型，也不能改出别的。
          return ticket != null && ticket.consist().isPresent()
              ? SpawnChoice.blocked("consist=" + ticket.consist().get() + ":no-consist-plans")
              : SpawnChoice.legacy();
        }

        @Override
        public boolean acceptsForRoute(UUID routeId, LayoverRegistry.LayoverCandidate candidate) {
          return true;
        }

        @Override
        public void onDispatched(SpawnTicket ticket, String trainName) {}
      };

  /**
   * 折返复用：去掉车型不许跑这条 route 的车，再把该优先的车型排到前面；同一车型的车保持原来的先后（先到先走）。
   *
   * @param ticket 票
   * @param candidates 已就绪、已过交路闸的候选，按到达先后
   * @return 过滤并排序后的候选
   */
  List<LayoverRegistry.LayoverCandidate> orderReuseCandidates(
      SpawnTicket ticket, List<LayoverRegistry.LayoverCandidate> candidates);

  /**
   * 新车出库：这张票出什么车型。只在主线程调用（要查 TrainCarts 的出车上限）。
   *
   * @param ticket 票
   * @return 裁决
   */
  SpawnChoice chooseSpawn(SpawnTicket ticket);

  /**
   * 这辆车能不能走这条 route（回收派 RETURN 用：车库只收方案里的车型）。
   *
   * @param routeId route
   * @param candidate 待命车
   * @return 能走
   */
  boolean acceptsForRoute(UUID routeId, LayoverRegistry.LayoverCandidate candidate);

  /**
   * 票已派给这辆车（出库或复用）：按实际车型记一班。
   *
   * @param ticket 票
   * @param trainName 列车名
   */
  void onDispatched(SpawnTicket ticket, String trainName);

  /**
   * 出车裁决。
   *
   * @param kind 种类
   * @param pattern 要出的编组写法；仅 {@link Kind#CHOSEN}
   * @param tags 出车后要写到车上的标签（车种、加减速等）；仅 {@link Kind#CHOSEN}
   * @param reason 出不了车的原因；仅 {@link Kind#BLOCKED}
   */
  record SpawnChoice(Kind kind, Optional<String> pattern, Map<String, String> tags, String reason) {

    /** 裁决种类。 */
    public enum Kind {
      /** route 没绑方案：按旧规则取编组。 */
      LEGACY,
      /** 按方案选定了车型。 */
      CHOSEN,
      /** 绑了方案，但现在一个车型都出不了（都到了出车上限，或档案都解析不出来）。 */
      BLOCKED
    }

    public SpawnChoice {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(pattern, "pattern");
      tags = tags == null ? Map.of() : Map.copyOf(tags);
      reason = reason == null ? "" : reason;
    }

    /** 按旧规则。 */
    public static SpawnChoice legacy() {
      return new SpawnChoice(Kind.LEGACY, Optional.empty(), Map.of(), "");
    }

    /** 选定车型。 */
    public static SpawnChoice chosen(String pattern, Map<String, String> tags) {
      return new SpawnChoice(Kind.CHOSEN, Optional.of(pattern), tags, "");
    }

    /** 出不了车。 */
    public static SpawnChoice blocked(String reason) {
      return new SpawnChoice(Kind.BLOCKED, Optional.empty(), Map.of(), reason);
    }
  }
}
