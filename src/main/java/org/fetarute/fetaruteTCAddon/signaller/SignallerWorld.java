package org.fetarute.fetaruteTCAddon.signaller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/** 信号员模块看到的外部世界：控制区目录、调度图、占用、列车进度与玩家位置。只读（除了 {@link #reevaluate} 与改停股道），只在主线程调用； 测试用假的实现。 */
interface SignallerWorld {

  /** 控制区：名称与成员；找不到时为空。 */
  Optional<Zone> zone(String operator, String zone);

  /** 各世界当前的调度图快照。 */
  Map<UUID, RailGraph> graphs();

  /** 一个世界当前的调度图快照（闸每次都要问，不复制整张表）。 */
  default Optional<RailGraph> graph(UUID worldId) {
    return Optional.ofNullable(graphs().get(worldId));
  }

  /** 全部占用。 */
  List<OccupancyClaim> claims();

  /** 全部列车的交路进度（列车名 → 进度）。 */
  Map<String, RouteProgressRegistry.RouteProgressEntry> progress();

  /** 交路定义。 */
  Optional<RouteDefinition> route(UUID routeUuid);

  /** 列车正在停站（停站计时中或挂着发车门控）。 */
  boolean dwelling(String trainName);

  /** 列车表与车次窗用的信息。 */
  TrainInfo info(String trainName);

  /** 立刻重评这列车的信号与出站门控（发许可、放行扣车之后）。 */
  void reevaluate(String trainName);

  /** 改停股道。 */
  SignallerPlatformDesk platforms();

  /** 在线玩家此刻的位置与状态；不在线时为空。 */
  Optional<PlayerSpot> player(UUID player);

  /**
   * 控制区。
   *
   * @param operator 运营商代码（大写）
   * @param code 控制区代码
   * @param name 名称
   * @param members 成员车站与车库
   */
  record Zone(String operator, String code, String name, List<YardArea.Member> members) {
    public Zone {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(name, "name");
      members = List.copyOf(members);
    }
  }

  /**
   * @param label 车次号；没有时为列车名
   * @param lineColor 线路色
   * @param delaySeconds 晚点秒数（早到为负）；不按表运行时为空
   */
  record TrainInfo(String label, Optional<Integer> lineColor, OptionalLong delaySeconds) {
    public TrainInfo {
      Objects.requireNonNull(label, "label");
      lineColor = lineColor == null ? Optional.empty() : lineColor;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    }
  }

  /**
   * 玩家的位置与状态。
   *
   * @param name 玩家名
   * @param world 所在世界
   * @param x x
   * @param y y
   * @param z z
   * @param fit 活着（死亡算不在岗；换游戏模式由事件另行处理）
   */
  record PlayerSpot(String name, UUID world, double x, double y, double z, boolean fit) {}
}
