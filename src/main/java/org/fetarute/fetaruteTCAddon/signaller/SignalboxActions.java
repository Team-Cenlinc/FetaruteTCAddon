package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerTrainDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 值守的信号员在控制台上的一次点击：选车、排列与取消进路、扣车与放行、自动通过、人工与自动切换、回复呼叫、改停股道。
 *
 * <p>信号员只能把“放行”变成“暂不放行”或把自己拦下的车放回内核去判：不能开放内核拒绝的进路、不能释放别的车的占用、不能扳道岔。
 *
 * <p>按不了的操作（按钮灰显，或画面与此刻状态对不上）都在标题栏提示原因，不静默忽略。
 */
final class SignalboxActions {

  private final SignallerService service;
  private final SignalBox box;
  private final ZoneMap map;
  private final Instant now;
  private final SignalboxState shown;
  private final boolean mayCancel;

  /**
   * @param shown 此刻控制台上的画面：按钮可用与否、选中的车、可改停的股道都以它为准
   * @param mayCancel 点的人有没有取消车次的权限
   */
  SignalboxActions(
      SignallerService service,
      SignalBox box,
      ZoneMap map,
      Instant now,
      SignalboxState shown,
      boolean mayCancel) {
    this.service = service;
    this.box = box;
    this.map = map;
    this.now = now;
    this.shown = shown;
    this.mayCancel = mayCancel;
  }

  void apply(ConsoleTarget target) {
    boolean held =
        box.lastClick != null && now.isBefore(box.lastClick.plus(SignallerService.HELD_REPEAT));
    box.lastClick = now;
    // 按住右键的连发：除了选车（重复选同一列车无妨），一律不算
    if (held && !(target instanceof ConsoleTarget.Train)) {
      return;
    }
    switch (target) {
      case ConsoleTarget.Deselect ignored -> {
        if (box.replying) {
          box.replying = false;
        } else if (box.trainOps) {
          box.trainOps = false;
        } else {
          box.selected = null;
        }
      }
      case ConsoleTarget.Train train -> {
        String key = SignallerService.key(train.trainName());
        if (!key.isEmpty()) {
          box.replying = false;
          // 列车操作只对着原来那辆车：改选后回到第一页，免得对着新选的车误按
          if (!key.equals(box.selected)) {
            box.trainOps = false;
          }
          service.select(box, key);
          service.examine(box, SignallerExaminer.Event.SELECTED);
        }
      }
      case ConsoleTarget.Press press -> {
        if (shown.enabled(press.button())) {
          press(press.button());
        } else {
          refuse(press.button());
        }
      }
      case ConsoleTarget.Act act -> {
        // 只在正显示第二页的时候做：旧画面上的点击不能落到第一页的按钮所对的操作上
        if (shown.trainOps().isEmpty() || !box.trainOps) {
          return;
        }
        switch (act.action()) {
          case BACK -> box.trainOps = false;
          case CANCEL -> cancelTrip();
          case SKIP -> skipStop();
        }
      }
      case ConsoleTarget.CallBadge ignored -> {
        box.replying = !box.calls.isEmpty();
        if (box.replying) {
          box.trainOps = false;
        }
        // 回复时顺带选中呼叫的车：回复完要给它排进路时不用再选
        if (box.replying) {
          service.select(box, SignallerService.key(box.calls.peekFirst().trainName()));
        }
      }
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
        } else {
          service.notice(box, SignalboxState.NoticeKind.PLATFORM_UNAVAILABLE, train);
        }
      }
      case ConsoleTarget.Signal signal -> clearAutoPass(signal.exit());
    }
    // 离开第二页（返回、改选、取消选择、去回复）就撤销“待确认”：再进来要重新按两下
    if (!box.trainOps) {
      box.cancelArm = null;
    }
  }

  /** 按了灰显的按钮：说明为什么按不了。 */
  private void refuse(SignalboxState.Button button) {
    if (button == SignalboxState.Button.MODE) {
      return;
    }
    Optional<String> train = shown.selected();
    if (train.isEmpty()) {
      service.notice(box, SignalboxState.NoticeKind.NO_SELECTION, Optional.empty());
      return;
    }
    String key = SignallerService.key(train.get());
    SignalBox.Permit permit = box.permits.get(key);
    SignalboxState.NoticeKind kind =
        switch (button) {
          case SET_ROUTE -> !box.manual()
              ? SignalboxState.NoticeKind.AUTO_MODE
              : permit != null
                  ? SignalboxState.NoticeKind.ALREADY_ROUTED
                  : SignalboxState.NoticeKind.IN_ZONE;
          case CANCEL_ROUTE -> permit == null
              ? SignalboxState.NoticeKind.NO_PERMIT
              : SignalboxState.NoticeKind.ROUTE_LOCKED;
          case HOLD -> box.cancelling.containsKey(key)
              ? SignalboxState.NoticeKind.CANCEL_PENDING
              : box.holds.containsKey(key)
                  ? SignalboxState.NoticeKind.ALREADY_HELD
                  : SignalboxState.NoticeKind.NOT_DWELLING;
          case TRAIN_OPS -> SignalboxState.NoticeKind.NO_SELECTION;
          case AUTO_PASS -> !box.manual()
              ? SignalboxState.NoticeKind.AUTO_MODE
              : SignalboxState.NoticeKind.NO_ROUTE;
          case MODE -> SignalboxState.NoticeKind.AUTO_MODE;
        };
    service.notice(box, kind, train);
  }

  private void press(SignalboxState.Button button) {
    if (button == SignalboxState.Button.MODE) {
      toggleMode();
      return;
    }
    Optional<String> train = selectedName();
    if (train.isEmpty()) {
      service.notice(box, SignalboxState.NoticeKind.NO_SELECTION, Optional.empty());
      return;
    }
    String key = SignallerService.key(train.get());
    service.refreshHoldings();
    switch (button) {
      case SET_ROUTE -> setRoute(train.get(), key);
      case CANCEL_ROUTE -> cancelRoute(key);
      case HOLD -> {
        if (box.holds.containsKey(key)) {
          release(key);
        } else {
          hold(train.get(), key);
        }
      }
      case AUTO_PASS -> autoPass(train.get(), key);
      case TRAIN_OPS -> box.trainOps = true;
      case MODE -> toggleMode();
    }
  }

  /** 排列进路：发许可；进路上有别的车占着时提示被谁挡住（许可保持排列中，等内核放行）。排列后选中下一列在入口等的车（按等候先后），连着处理几列车时每列只要按一下。 */
  private void setRoute(String trainName, String key) {
    if (!box.manual() || map == null) {
      service.notice(box, SignalboxState.NoticeKind.AUTO_MODE, Optional.of(trainName));
      return;
    }
    if (box.permits.containsKey(key)) {
      service.notice(box, SignalboxState.NoticeKind.ALREADY_ROUTED, Optional.of(trainName));
      return;
    }
    if (service.presence(box, key).isPresent()) {
      service.notice(box, SignalboxState.NoticeKind.IN_ZONE, Optional.of(trainName));
      return;
    }
    SignalBox.Waiting waiting = box.waiting.get(key);
    boolean waited = waiting != null && !service.stale(waiting, now);
    Optional<SignalBox.Route> route = routeOf(trainName, key);
    if (service.approachLocked(box, route, now)) {
      service.notice(box, SignalboxState.NoticeKind.APPROACH_LOCKED, Optional.of(trainName));
      return;
    }
    service.pairWithRival(box, map, trainName, route, now);
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
    box.waiting.values().stream()
        .filter(next -> !service.stale(next, now))
        .filter(next -> !box.permits.containsKey(SignallerService.key(next.trainName)))
        .min(Comparator.comparing((SignalBox.Waiting next) -> next.since))
        .ifPresent(next -> service.select(box, SignallerService.key(next.trainName)));
  }

  /** 取消进路：只对还没锁闭的许可；车已经拿到控制区资源就是锁闭了，不能取消。 */
  private void cancelRoute(String key) {
    SignalBox.Permit permit = box.permits.get(key);
    if (permit == null) {
      service.notice(box, SignalboxState.NoticeKind.NO_PERMIT, Optional.empty());
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
    lockApproach(permit, key);
    // 原来在入口等着的车按原来的开始时刻接着等：反复排列再取消不能把代排计时清零
    permit.waitingSince.ifPresent(
        since -> {
          SignalBox.Waiting waiting = new SignalBox.Waiting(permit.trainName, since, permit.route);
          waiting.lastSeen = now;
          box.waiting.put(key, waiting);
          // 原来那段等待已过代排时限：车再来问闸就当场代排，先说清楚，免得看上去取消没起作用
          SignallerConfig settings = service.settings();
          // 接近锁闭中不代排：那时提示的是锁闭，不说“将立即代排”
          if (settings.autoRouteSeconds() > 0
              && !now.isBefore(since.plus(settings.autoRoute()))
              && !service.approachLocked(box, permit.route, now)) {
            service.notice(
                box, SignalboxState.NoticeKind.AUTO_ROUTE_DUE, Optional.of(permit.trainName));
          }
        });
    service.reevaluate(permit.trainName);
  }

  /**
   * 接近锁闭：取消的进路上车已在接近（已从区外最后一站开出、或已停在入口），这个入口要等 {@code approach-lock-seconds} 才能再排进路（现实的时间锁闭）。
   * 车还停在区外上一站、或还要先在区外别的站停（提前排的进路）时不锁。代排时限在锁闭期间到了的车，锁一解除由系统放行，不算代排。
   */
  private void lockApproach(SignalBox.Permit permit, String key) {
    int seconds = service.settings().approachLockSeconds();
    if (seconds <= 0 || permit.route.isEmpty() || map == null) {
      return;
    }
    // 已停在入口，或已从区外最后一站开出、正在接近；还停在那一站（没动）的不锁
    boolean onApproach =
        permit.waitingSince.isPresent()
            || (!service.world().dwelling(permit.trainName)
                && service.world().progress().values().stream()
                    .filter(entry -> SignallerService.key(entry.trainName()).equals(key))
                    .anyMatch(entry -> service.approaching(map, entry)));
    if (!onApproach) {
      return;
    }
    EdgeId entry = permit.route.get().entry();
    box.approachLocks.put(EdgeId.undirected(entry.a(), entry.b()), now.plusSeconds(seconds));
    service.notice(box, SignalboxState.NoticeKind.APPROACH_LOCKING, Optional.of(permit.trainName));
  }

  /** 扣车：只扣在区内停站的车。 */
  private void hold(String trainName, String key) {
    if (box.holds.containsKey(key)) {
      service.notice(box, SignalboxState.NoticeKind.ALREADY_HELD, Optional.of(trainName));
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
    service.examine(box, SignallerExaminer.Event.HELD);
  }

  private void release(String key) {
    SignalBox.Hold hold = box.holds.remove(key);
    if (hold != null) {
      service.creditHold(box, key, hold, now);
    }
    if (hold == null) {
      service.notice(box, SignalboxState.NoticeKind.NOT_HELD, Optional.empty());
      return;
    }
    service.examine(box, SignallerExaminer.Event.RELEASED);
    service.reevaluate(hold.trainName());
  }

  /** 自动通过：选中车这条进路（同一入口、同一股道）以后的车来了就放；再按一下取消。 */
  private void autoPass(String trainName, String key) {
    if (!box.manual()) {
      service.notice(box, SignalboxState.NoticeKind.AUTO_MODE, Optional.of(trainName));
      return;
    }
    Optional<SignalBox.Route> route =
        Optional.ofNullable(box.permits.get(key))
            .flatMap(permit -> permit.route)
            .or(() -> Optional.ofNullable(box.waiting.get(key)).flatMap(w -> w.route))
            .or(() -> service.routeAhead(map, trainName));
    if (route.isEmpty()) {
      service.notice(box, SignalboxState.NoticeKind.NO_ROUTE, Optional.of(trainName));
      return;
    }
    if (box.autoPasses.remove(route.get())) {
      service.notice(box, SignalboxState.NoticeKind.AUTO_PASS_OFF, Optional.empty());
      service.examine(box, SignallerExaminer.Event.AUTO_PASS_OFF);
      return;
    }
    box.autoPasses.add(route.get());
    service.notice(box, SignalboxState.NoticeKind.AUTO_PASS_ON, Optional.empty());
    service.examine(box, SignallerExaminer.Event.AUTO_PASS_ON);
    // 这条进路上正在等的车立刻放
    for (SignalBox.Waiting waiting : List.copyOf(box.waiting.values())) {
      if (waiting.route.equals(route)
          && !service.stale(waiting, now)
          && !service.approachLocked(box, waiting.route, now)) {
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

  /** 人工与自动切换。转自动时放回所有在等的车（自动通过留着，转回人工照用）；调度图未就绪时控制区本来就按自动放行，两个方向都只提示。 */
  private void toggleMode() {
    if (map == null) {
      service.notice(box, SignalboxState.NoticeKind.GRAPH_NOT_READY, Optional.empty());
      return;
    }
    if (box.manual()) {
      service.toAuto(box);
      service.examine(box, SignallerExaminer.Event.AUTO);
      return;
    }
    service.setMode(box, SignalboxState.Mode.MANUAL);
    service.examine(box, SignallerExaminer.Event.MANUAL);
  }

  /** 点了没有车在等、设了自动通过的入口信号：撤掉从这个入口进来的自动通过。 */
  private void clearAutoPass(EdgeId exit) {
    EdgeId entry = EdgeId.undirected(exit.a(), exit.b());
    boolean removed =
        box.autoPasses.removeIf(
            route -> EdgeId.undirected(route.entry().a(), route.entry().b()).equals(entry));
    if (removed) {
      service.notice(box, SignalboxState.NoticeKind.AUTO_PASS_OFF, Optional.empty());
      service.examine(box, SignallerExaminer.Event.AUTO_PASS_OFF);
    }
  }

  /** 回复最早的那个呼叫：回复发给呼叫的那名乘务员，要配得上呼叫的原因（{@link CallReplies}），不符的计数并告诉信号员该怎么回。模拟呼叫的回复不发给任何人。 */
  private void answer(SignalboxState.Reply reply) {
    SignalBox.Call call = box.calls.pollFirst();
    box.replying = false;
    if (call == null) {
      return;
    }
    box.restartSimHead(call, now);
    boolean fits = CallReplies.fits(call.reason(), reply, routable(call.trainName()));
    String callKey = SignallerService.key(call.trainName());
    // 已有许可（例如已被代排）或已在区内的车没有进路可排：不记承诺，免得代排之外再罚一次
    if (fits
        && reply == SignalboxState.Reply.ROUTE_SETTING
        && box.manual()
        && !box.permits.containsKey(callKey)
        && service.presence(box, callKey).isEmpty()) {
      box.promises.put(callKey, new SignalBox.Promise(call, now.plus(SignallerService.PROMISE)));
    }
    if (box.duty != null && call.sim()) {
      if (fits) {
        box.duty.simRight++;
      } else {
        box.duty.simWrong++;
      }
    } else if (box.duty != null) {
      box.duty.callsAnswered++;
      if (!fits) {
        box.duty.callsWrong++;
      }
    }
    service.examine(
        box,
        call.sim()
            ? (fits ? SignallerExaminer.Event.SIM_RIGHT : SignallerExaminer.Event.SIM_WRONG)
            : call.drill()
                ? (fits
                    ? SignallerExaminer.Event.DRILL_ANSWERED
                    : SignallerExaminer.Event.DRILL_WRONG)
                : (fits
                    ? SignallerExaminer.Event.CALL_ANSWERED
                    : SignallerExaminer.Event.CALL_WRONG));
    String train = service.world().info(call.trainName()).label();
    if (!call.drill()) {
      service
          .messenger()
          .alert(
              call.caller(),
              "signaller.reply." + reply.name().toLowerCase(Locale.ROOT).replace('_', '-'),
              Map.of(
                  "zone",
                  service.zoneName(box),
                  "signaller",
                  box.duty == null ? "" : box.duty.playerName,
                  "train",
                  train));
    }
    if (!fits && box.duty != null) {
      service
          .messenger()
          .send(
              box.duty.player,
              "signaller.call.wrong-" + call.reason().key(),
              Map.of("train", train));
    }
    service.notice(
        box,
        fits ? SignalboxState.NoticeKind.REPLY_SENT : SignalboxState.NoticeKind.REPLY_WRONG,
        Optional.of(call.trainName()));
  }

  /** 排列进路要用的进路：在入口等着的按它等的那条，否则按交路往前展开。 */
  private Optional<SignalBox.Route> routeOf(String trainName, String key) {
    SignalBox.Waiting waiting = box.waiting.get(key);
    boolean waited = waiting != null && !service.stale(waiting, now);
    return waited && waiting.route.isPresent() ? waiting.route : service.routeAhead(map, trainName);
  }

  /** 此刻能给这列车排进路：车没被扣、没在停站，入口没在接近锁闭中，前方区内也没有别的车占着（信号前等候的回复按它判对错，与“排列进路”按下去会不会被拒同一口径）。 */
  private boolean routable(String trainName) {
    String key = SignallerService.key(trainName);
    if (box.holds.containsKey(key)) {
      return false;
    }
    if (map != null && service.approachLocked(box, routeOf(trainName, key), now)) {
      return false;
    }
    boolean stopped =
        shown.rows().stream()
            .anyMatch(
                row ->
                    row.trainName().equals(trainName)
                        && (row.status() == SignalboxState.RowStatus.DWELLING
                            || row.status() == SignalboxState.RowStatus.HELD));
    return !stopped && blocker(trainName, key).isEmpty();
  }

  /** 取消车次：第一下只记下等着确认，同一个人对同一列车再按一下才取消。两下都按此刻的现场复核：联挂中的一体车、车上有人而车又不在站台上停着时，都不能取消。 */
  private void cancelTrip() {
    Optional<String> train = selectedName();
    if (train.isEmpty() || box.duty == null) {
      return;
    }
    String key = SignallerService.key(train.get());
    if (box.cancelling.containsKey(key)) {
      // 已确认、正在倒计时：再按不算第二次
      service.notice(box, SignalboxState.NoticeKind.CANCEL_PENDING, train);
      return;
    }
    SignallerTrainDesk desk = service.world().trains();
    SignallerTrainDesk.Scene scene = desk.scene(train.get());
    Optional<SignalboxState.NoticeKind> refusal =
        !mayCancel
            ? Optional.of(SignalboxState.NoticeKind.CANCEL_NO_PERMISSION)
            : service.examining(box.duty.player)
                ? Optional.of(SignalboxState.NoticeKind.CANCEL_IN_EXAM)
                : !ours(train.get())
                    ? Optional.of(SignalboxState.NoticeKind.CANCEL_OUT_OF_ZONE)
                    : scene.coupled()
                        ? Optional.of(SignalboxState.NoticeKind.CANCEL_COUPLED)
                        : scene.playersAboard() && !scene.atPlatform()
                            ? Optional.of(SignalboxState.NoticeKind.CANCEL_OCCUPIED)
                            : Optional.empty();
    if (refusal.isPresent()) {
      box.cancelArm = null;
      service.notice(box, refusal.get(), train);
      return;
    }
    SignalBox.CancelArm arm = box.cancelArm;
    boolean armed =
        arm != null
            && arm.player().equals(box.duty.player)
            && arm.train().equals(key)
            && arm.live(now);
    if (!armed) {
      box.cancelArm = new SignalBox.CancelArm(box.duty.player, key, now);
      return;
    }
    if (now.isBefore(arm.at().plus(SignallerService.TOGGLE_REPEAT))) {
      return;
    }
    box.cancelArm = null;
    box.trainOps = false;
    box.duty.tripsCancelled++;
    service.examine(box, SignallerExaminer.Event.TRIP_CANCELLED);
    service.notice(box, SignalboxState.NoticeKind.TRIP_CANCELLED, train);
    service.logCancel(box, train.get());
    if (!scene.playersAboard()) {
      desk.cancel(train.get());
      return;
    }
    // 车上有人：先扣住、插播停运，倒计时到了再删
    box.holds.putIfAbsent(key, new SignalBox.Hold(train.get(), now));
    box.cancelling.put(
        key, new SignalBox.PendingCancel(train.get(), now.plus(SignallerService.CANCEL_COUNTDOWN)));
    desk.announceCancel(train.get());
  }

  /** 越站：晚点够门槛、下一个停车站归本楼管时，令越过下一个停车站；已下令的再按撤销。 */
  private void skipStop() {
    Optional<String> train = selectedName();
    if (train.isEmpty()) {
      return;
    }
    SignallerTrainDesk desk = service.world().trains();
    Optional<SignallerTrainDesk.Skip> skip = desk.skip(train.get());
    // 越站与撤销都只管本楼实际管着的车站：让给小区（或中心工位）的那一片归那边
    ZoneMap owned = service.owned(box);
    boolean ours = skip.isPresent() && owned != null && owned.inside(skip.get().station());
    if (skip.isPresent() && skip.get().ordered()) {
      if (box.duty != null && service.examOnly(box.duty.player)) {
        service.notice(box, SignalboxState.NoticeKind.SKIP_IN_EXAM, train);
        return;
      }
      if (!ours) {
        service.notice(box, SignalboxState.NoticeKind.SKIP_OUT_OF_ZONE, train);
        return;
      }
      Optional<SignallerTrainDesk.SkipRefusal> refusal = desk.revokeSkip(train.get());
      if (refusal.isPresent()) {
        service.notice(box, skipNotice(refusal.get()), train);
        return;
      }
      service.examine(box, SignallerExaminer.Event.SKIP_REVOKED);
      service.notice(box, SignalboxState.NoticeKind.SKIP_REVOKED, train);
      return;
    }
    if (box.duty != null && service.examOnly(box.duty.player)) {
      service.notice(box, SignalboxState.NoticeKind.SKIP_IN_EXAM, train);
      return;
    }
    if (desk.scene(train.get()).coupled()) {
      service.notice(box, SignalboxState.NoticeKind.SKIP_COUPLED, train);
      return;
    }
    OptionalLong delay = service.world().info(train.get()).delaySeconds();
    if (delay.isEmpty() || delay.getAsLong() < service.settings().skipMinDelaySeconds()) {
      service.notice(box, SignalboxState.NoticeKind.SKIP_NOT_LATE, train);
      return;
    }
    if (skip.isEmpty()) {
      service.notice(box, SignalboxState.NoticeKind.SKIP_NO_STOP, train);
      return;
    }
    if (!ours) {
      service.notice(box, SignalboxState.NoticeKind.SKIP_OUT_OF_ZONE, train);
      return;
    }
    Optional<SignallerTrainDesk.SkipRefusal> refusal =
        skip.get().refusal().or(() -> desk.orderSkip(train.get()));
    if (refusal.isPresent()) {
      service.notice(box, skipNotice(refusal.get()), train);
      return;
    }
    if (box.duty != null) {
      box.duty.skipsOrdered++;
    }
    SignalBox.Permit permit = box.permits.get(SignallerService.key(train.get()));
    if (permit != null) {
      permit.skipOrdered = true;
    }
    service.examine(box, SignallerExaminer.Event.SKIP_ORDERED);
    service.notice(box, SignalboxState.NoticeKind.SKIP_ORDERED, train);
  }

  private static SignalboxState.NoticeKind skipNotice(SignallerTrainDesk.SkipRefusal refusal) {
    return switch (refusal) {
      case NO_STOP -> SignalboxState.NoticeKind.SKIP_NO_STOP;
      case TERMINAL -> SignalboxState.NoticeKind.SKIP_TERMINAL;
      case DIRECTIVE -> SignalboxState.NoticeKind.SKIP_DIRECTIVE;
      case BRAKING -> SignalboxState.NoticeKind.SKIP_BRAKING;
      case CALL_DESTINATION -> SignalboxState.NoticeKind.SKIP_CALL_DESTINATION;
      case CREW_BOARDING -> SignalboxState.NoticeKind.SKIP_CREW_BOARDING;
    };
  }

  /** 这列车归本楼处置：已在区里（运行、停站或被扣）；还在接近、在入口等着的车停在区外，多半在邻区的站台上，归那边管。 */
  private boolean ours(String trainName) {
    return shown.rows().stream()
        .anyMatch(
            row ->
                row.trainName().equals(trainName)
                    && (row.status() == SignalboxState.RowStatus.IN_ZONE
                        || row.status() == SignalboxState.RowStatus.DWELLING
                        || row.status() == SignalboxState.RowStatus.HELD));
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
