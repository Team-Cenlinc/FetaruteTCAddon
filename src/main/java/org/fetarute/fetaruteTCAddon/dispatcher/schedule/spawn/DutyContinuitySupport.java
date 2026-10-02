package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 按交路运行时，一辆车前后两趟之间的衔接与编表排定的计划站台，供站牌展示。
 *
 * <p>交路规定了一辆车依次跑哪几趟：车停在终点时，它接下来开哪一趟已经确定；一张续班票要等哪辆车，也已经确定。 站牌据此在终点列出“这辆车 X
 * 站台几点开往哪里”，并把来车的晚点传过终点。只读，不改调度状态。
 */
public interface DutyContinuitySupport {

  /**
   * 跑完当前这一趟后，这辆车接下来要开的那一趟。
   *
   * @param trainName 列车名
   * @return 下一趟；不按交路运行、交路已跑完或那一趟已取消时为空
   */
  Optional<NextDeparture> nextDepartureOf(String trainName);

  /**
   * 这张票要等的车：本交路的车还没跑到这一班。
   *
   * @param ticket 已出的票或预测票
   * @return 那辆车的列车名；不是续班票、或交路没有车时为空
   */
  Optional<String> awaitedVehicleOf(SpawnTicket ticket);

  /**
   * 一张票的车次在某个停靠点的计划站台（编表时排定）。
   *
   * @param ticket 已出的票或预测票
   * @param stopIndex 停靠序号
   * @return 计划股道节点；不是表定票或没有计划时为空
   */
  Optional<String> plannedPlatformOf(SpawnTicket ticket, int stopIndex);

  /**
   * 接下来要开的那一趟。
   *
   * @param routeId 交路
   * @param plannedDeparture 起点计划发车
   * @param serviceTripId 与这一趟的票据相同的车次标识（{@link SpawnTicket#serviceTripId()}），站牌据此去重
   */
  record NextDeparture(UUID routeId, Instant plannedDeparture, String serviceTripId) {

    public NextDeparture {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(plannedDeparture, "plannedDeparture");
      Objects.requireNonNull(serviceTripId, "serviceTripId");
    }
  }
}
