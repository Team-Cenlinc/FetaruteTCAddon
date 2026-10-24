package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 值守中的模拟事件（常驻模拟呼叫）：服务器人少、信号员大部分时间只看着系统排进路时，隔一阵以一列与本楼有关的车的名义来一次呼叫。 原因按那列车此刻的实况选，答案也按回复那一刻的实况判（见
 * {@link CallReplies}）。事件一律不改变列车的运行，只考判断与沟通，对错另记成绩。
 *
 * <ul>
 *   <li>出题条件：有人值守，不在考试或练习中，人在控制台范围内，上岗满 {@link #WARMUP}，本楼没有待回复的呼叫、也没有回了“进路正在排列”还没兑现的模拟呼叫；
 *   <li>间隔：按平均间隔取指数分布，两题之间至少隔最小间隔；第一题从上岗满 {@link #WARMUP} 起算；到了时刻条件不满足就等到满足；
 *   <li>挑车：不借有真人驾驶员或车掌的车。在入口等许可的报信号前等候，被扣在站里的询问前方情况，在区内停站的报告在哪一站、哪一道停妥。
 * </ul>
 *
 * <p>随机数由服务注入，测试可以固定。只在主线程调用。
 */
final class SignallerEvents {

  /** 上岗满这么久才出第一题。 */
  static final Duration WARMUP = Duration.ofSeconds(60);

  /** 到了出题时刻却没有能借的车时，隔多久再挑。 */
  static final Duration RETRY = Duration.ofSeconds(5);

  /** 均匀随机数的上限：取对数时避开 1。 */
  private static final double UNIFORM_CEILING = 0.999_999;

  private final SignallerService service;

  SignallerEvents(SignallerService service) {
    this.service = service;
  }

  /**
   * 一拍：到了出题时刻、条件都满足就出一题。
   *
   * @return 出了题
   */
  boolean tick(SignalBox box, SignallerConfig.Events settings, Instant now) {
    SignalBox.Duty duty = box.duty;
    if (!settings.enabled() || duty == null) {
      return false;
    }
    if (box.nextEventAt == null) {
      box.nextEventAt = duty.start.plus(WARMUP).plus(gap(settings, false));
    }
    if (now.isBefore(box.nextEventAt) || !ready(box, duty)) {
      return false;
    }
    Optional<Pick> pick = pick(box, now);
    if (pick.isEmpty()) {
      // 挑车要把整张列车表推一遍：没有能借的车时隔一会儿再看，不每拍都推
      box.nextEventAt = now.plus(RETRY);
      return false;
    }
    service.simulateCall(
        box, pick.get().trainName(), pick.get().reason(), pick.get().values(), now);
    box.nextEventAt = now.plus(gap(settings, true));
    return true;
  }

  /** 到下一题的间隔：指数分布；{@code atLeastMinimum} 时不短于最小间隔。 */
  private Duration gap(SignallerConfig.Events settings, boolean atLeastMinimum) {
    double uniform = Math.max(0.0, Math.min(UNIFORM_CEILING, service.random().nextDouble()));
    long seconds = Math.round(-settings.meanIntervalSeconds() * Math.log(1.0 - uniform));
    if (atLeastMinimum) {
      seconds = Math.max(settings.minGapSeconds(), seconds);
    }
    return Duration.ofSeconds(seconds);
  }

  private boolean ready(SignalBox box, SignalBox.Duty duty) {
    return duty.awaySince == null
        && !service.examining(duty.player)
        && box.calls.isEmpty()
        && box.promises.values().stream().noneMatch(promise -> promise.call().sim());
  }

  /** 一道题：以哪列车的名义、什么原因、来电提醒另带的占位符。 */
  private record Pick(String trainName, CallReason reason, Map<String, String> values) {}

  private Optional<Pick> pick(SignalBox box, Instant now) {
    List<SignalboxState.TrainRow> rows =
        new SignalboxStates(service, box, service.owned(box), now).build().rows();
    List<Pick> picks = new ArrayList<>();
    for (SignalboxState.TrainRow row : rows) {
      String train = row.trainName();
      if (service.world().crewed(train)) {
        continue;
      }
      switch (row.status()) {
        case WAITING -> picks.add(new Pick(train, CallReason.SIGNAL, Map.of()));
        case HELD -> picks.add(new Pick(train, CallReason.QUERY, Map.of()));
        case DWELLING -> stoppedAt(train)
            .ifPresent(values -> picks.add(new Pick(train, CallReason.REPORT, values)));
        default -> {}
      }
    }
    if (picks.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(picks.get(service.random().nextInt(picks.size())));
  }

  /** 停站的车停在哪一站、哪一道（报告停妥用）；最后经过的节点不是车站本体（站台股道）时为空。 */
  private Optional<Map<String, String>> stoppedAt(String trainName) {
    String key = SignallerService.key(trainName);
    for (RouteProgressRegistry.RouteProgressEntry entry : service.world().progress().values()) {
      if (!key.equals(SignallerService.key(entry.trainName()))) {
        continue;
      }
      Optional<NodeId> node = entry.lastPassedGraphNode();
      if (node.isEmpty()) {
        return Optional.empty();
      }
      Optional<RouteTerminals.StationRef> ref = RouteTerminals.stationRefOfNode(node.get().value());
      if (ref.isEmpty()) {
        return Optional.empty();
      }
      String station =
          service.world().stationNameOfNode(node.get().value()).orElse(ref.get().stationCode());
      return Optional.of(
          Map.of("station", station, "track", RouteTerminals.platformOf(node.get().value())));
    }
    return Optional.empty();
  }
}
