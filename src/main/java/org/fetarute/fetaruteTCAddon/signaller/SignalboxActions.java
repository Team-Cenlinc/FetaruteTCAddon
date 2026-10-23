package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 值守的信号员在控制台上的一次点击：选车、排列与取消进路、扣车与放行、自动通过、人工与自动切换、回复呼叫、改停股道。
 *
 * <p>信号员只能把“放行”变成“暂不放行”或把自己拦下的车放回内核去判：不能开放内核拒绝的进路、不能释放别的车的占用、不能扳道岔。
 */
final class SignalboxActions {

  private final SignallerService service;
  private final SignalBox box;
  private final ZoneMap map;
  private final Instant now;
  private final SignalboxState shown;

  /**
   * @param shown 此刻控制台上的画面：按钮可用与否、选中的车、可改停的股道都以它为准
   */
  SignalboxActions(
      SignallerService service, SignalBox box, ZoneMap map, Instant now, SignalboxState shown) {
    this.service = service;
    this.box = box;
    this.map = map;
    this.now = now;
    this.shown = shown;
  }

  void apply(ConsoleTarget target) {
    switch (target) {
      case ConsoleTarget.Deselect ignored -> {
        if (box.replying) {
          box.replying = false;
        } else {
          box.selected = null;
        }
      }
      case ConsoleTarget.Train train -> {
        String key = SignallerService.key(train.trainName());
        if (!key.isEmpty()) {
          box.replying = false;
          service.select(box, key);
        }
      }
      case ConsoleTarget.Press press -> {
        if (shown.enabled(press.button())) {
          press(press.button());
        }
      }
      case ConsoleTarget.CallBadge ignored -> box.replying = !box.calls.isEmpty();
      case ConsoleTarget.Answer answer -> {
        // 只在正显示回复的时候回：呼叫超时出队后，旧画面上的回复不能落到下一名驾驶员头上
        if (shown.replying() && box.replying) {
          answer(answer.reply());
        }
      }
      case ConsoleTarget.Platform platform -> {
        Optional<String> train = selectedName();
        boolean offered =
            shown.platforms().stream().anyMatch(choice -> choice.node().equals(platform.node()));
        if (train.isPresent() && offered) {
          service.choosePlatform(box, train.get(), platform.node());
        }
      }
    }
  }

  private void press(SignalboxState.Button button) {
    if (button == SignalboxState.Button.MODE) {
      toggleMode();
      return;
    }
    Optional<String> train = selectedName();
    if (train.isEmpty()) {
      return;
    }
    String key = SignallerService.key(train.get());
    service.refreshHoldings();
    switch (button) {
      case SET_ROUTE -> setRoute(train.get(), key);
      case CANCEL_ROUTE -> cancelRoute(key);
      case HOLD -> hold(train.get(), key);
      case RELEASE -> release(key);
      case AUTO_PASS -> autoPass(train.get(), key);
      case MODE -> toggleMode();
    }
  }

  /** 排列进路：发许可；进路上有别的车占着时提示被谁挡住（许可保持排列中，等内核放行）。 */
  private void setRoute(String trainName, String key) {
    if (!box.manual() || map == null || box.permits.containsKey(key)) {
      return;
    }
    if (service.presence(box, key).isPresent()) {
      return;
    }
    SignalBox.Waiting waiting = box.waiting.get(key);
    boolean waited = waiting != null && !service.stale(waiting, now);
    Optional<SignalBox.Route> route =
        waited && waiting.route.isPresent() ? waiting.route : routeAhead(trainName);
    service.grant(
        box,
        trainName,
        SignalBox.Source.SIGNALLER,
        now,
        waited ? Optional.of(waiting.since) : Optional.empty(),
        route);
    blocker(trainName, key)
        .ifPresent(
            other ->
                service.notice(box, SignalboxState.NoticeKind.ROUTE_BLOCKED, Optional.of(other)));
  }

  /** 取消进路：只对还没锁闭的许可；车已经拿到控制区资源就是锁闭了，不能取消。 */
  private void cancelRoute(String key) {
    SignalBox.Permit permit = box.permits.get(key);
    if (permit == null) {
      return;
    }
    if (permit.state == SignalBox.PermitState.LOCKED
        || service.presence(box, key).filter(SignallerService.Presence::moving).isPresent()) {
      // 锁闭与否、计不计经手都交给每秒巡检判，这里只提示
      service.notice(box, SignalboxState.NoticeKind.ROUTE_LOCKED, Optional.of(permit.trainName));
      return;
    }
    box.permits.remove(key);
    if (box.duty != null && permit.source == SignalBox.Source.SIGNALLER) {
      box.duty.routesSet = Math.max(0, box.duty.routesSet - 1);
    }
    // 原来在入口等着的车按原来的开始时刻接着等：反复排列再取消不能把代排计时清零
    permit.waitingSince.ifPresent(
        since -> {
          SignalBox.Waiting waiting = new SignalBox.Waiting(permit.trainName, since, permit.route);
          waiting.lastSeen = now;
          box.waiting.put(key, waiting);
        });
    service.reevaluate(permit.trainName);
  }

  /** 扣车：只扣在区内停站的车。 */
  private void hold(String trainName, String key) {
    if (box.holds.containsKey(key)) {
      return;
    }
    boolean inside = service.presence(box, key).isPresent();
    if (!inside || !service.world().dwelling(trainName)) {
      service.notice(box, SignalboxState.NoticeKind.NOT_DWELLING, Optional.of(trainName));
      return;
    }
    box.holds.put(key, new SignalBox.Hold(trainName, now));
    if (box.duty != null) {
      box.duty.holds++;
    }
  }

  private void release(String key) {
    SignalBox.Hold hold = box.holds.remove(key);
    if (hold != null) {
      service.reevaluate(hold.trainName());
    }
  }

  /** 自动通过：选中车这条进路（同一入口、同一股道）以后的车来了就放；再按一下取消。 */
  private void autoPass(String trainName, String key) {
    if (!box.manual()) {
      return;
    }
    Optional<SignalBox.Route> route =
        Optional.ofNullable(box.permits.get(key))
            .flatMap(permit -> permit.route)
            .or(() -> Optional.ofNullable(box.waiting.get(key)).flatMap(w -> w.route))
            .or(() -> routeAhead(trainName));
    if (route.isEmpty()) {
      service.notice(box, SignalboxState.NoticeKind.NO_ROUTE, Optional.of(trainName));
      return;
    }
    if (box.autoPasses.remove(route.get())) {
      service.notice(box, SignalboxState.NoticeKind.AUTO_PASS_OFF, Optional.empty());
      return;
    }
    box.autoPasses.add(route.get());
    service.notice(box, SignalboxState.NoticeKind.AUTO_PASS_ON, Optional.empty());
    // 这条进路上正在等的车立刻放
    for (SignalBox.Waiting waiting : List.copyOf(box.waiting.values())) {
      if (waiting.route.equals(route) && !service.stale(waiting, now)) {
        service.grant(
            box,
            waiting.trainName,
            SignalBox.Source.AUTO_PASS,
            now,
            Optional.of(waiting.since),
            waiting.route);
      }
    }
  }

  /** 人工与自动切换。转自动时放回所有在等的车；转人工要等控制区推得出来。 */
  private void toggleMode() {
    if (box.manual()) {
      List<String> wake = new ArrayList<>();
      for (SignalBox.Waiting waiting : box.waiting.values()) {
        wake.add(waiting.trainName);
      }
      service.setMode(box, SignalboxState.Mode.AUTO);
      box.permits.clear();
      box.waiting.clear();
      box.autoPasses.clear();
      wake.forEach(service::reevaluate);
      return;
    }
    if (map == null) {
      service.notice(box, SignalboxState.NoticeKind.GRAPH_NOT_READY, Optional.empty());
      return;
    }
    service.setMode(box, SignalboxState.Mode.MANUAL);
  }

  /** 回复最早的那个呼叫。 */
  private void answer(SignalboxState.Reply reply) {
    SignalBox.Call call = box.calls.pollFirst();
    box.replying = false;
    if (call == null) {
      return;
    }
    if (box.duty != null) {
      box.duty.callsAnswered++;
    }
    service
        .messenger()
        .alert(
            call.driver(),
            "signaller.reply." + reply.name().toLowerCase(Locale.ROOT).replace('_', '-'),
            Map.of(
                "zone",
                service.zoneName(box),
                "signaller",
                box.duty == null ? "" : box.duty.playerName,
                "train",
                service.world().info(call.trainName()).label()));
    service.notice(box, SignalboxState.NoticeKind.REPLY_SENT, Optional.of(call.trainName()));
  }

  private Optional<String> selectedName() {
    if (box.selected == null) {
      return Optional.empty();
    }
    String key = box.selected;
    SignalBox.Waiting waiting = box.waiting.get(key);
    if (waiting != null) {
      return Optional.of(waiting.trainName);
    }
    SignalBox.Permit permit = box.permits.get(key);
    if (permit != null) {
      return Optional.of(permit.trainName);
    }
    SignalBox.Hold hold = box.holds.get(key);
    if (hold != null) {
      return Optional.of(hold.trainName());
    }
    return service.world().progress().values().stream()
        .map(entry -> entry.trainName())
        .filter(name -> SignallerService.key(name).equals(key))
        .findFirst();
  }

  private Optional<SignalBox.Route> routeAhead(String trainName) {
    if (map == null) {
      return Optional.empty();
    }
    return service
        .ahead(map, trainName)
        .flatMap(ahead -> ahead.entry().map(entry -> new SignalBox.Route(entry, ahead.target())));
  }

  /** 选中车要走的区内区间上，占着它的别的车（取第一个）。 */
  private Optional<String> blocker(String trainName, String key) {
    if (map == null) {
      return Optional.empty();
    }
    Optional<RouteAhead> ahead = service.ahead(map, trainName);
    if (ahead.isEmpty()) {
      return Optional.empty();
    }
    Set<OccupancyResource> path = new HashSet<>();
    for (EdgeId edge : ahead.get().zoneEdges()) {
      path.add(OccupancyResource.forEdge(edge));
      path.add(OccupancyResource.forNode(edge.a()));
      path.add(OccupancyResource.forNode(edge.b()));
    }
    for (OccupancyClaim claim : service.world().claims()) {
      if (path.contains(claim.resource())
          && !SignallerService.key(claim.trainName()).equals(key)
          && claim.role() != ClaimRole.LOOKAHEAD_PREVIEW
          && claim.role() != ClaimRole.QUEUE_POSITION) {
        return Optional.of(service.world().info(claim.trainName()).label());
      }
    }
    return Optional.empty();
  }
}
