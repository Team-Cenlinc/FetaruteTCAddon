package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ShuntingRegistry;

/**
 * 扳手推车：把一列车以慢速推向前方的车，空隙小到能挂时停车并挂上。
 *
 * <p>推车期间两列车都登记为调车中（{@link ShuntingRegistry}）：调度让出控制，不写限速、不发车、不刹车，发车票与回收也不接它们。推车用自己的动作逐 tick
 * 定速，开着车钩的是车尾一端时先调头， 没挂上就调回原朝向。推过了“空隙 + 余量”仍没接上、推不动、或任一方列车不在了，都停车报失败。看门狗每半秒查一次：动作被别处清掉或列车被卸载时照样收尾，
 * 调车登记一定会撤销。只在服务器主线程使用。
 */
final class ShuntPusher {

  /** 空隙小到这么多格就停车去挂。 */
  static final double LINK_GAP_BLOCKS = 0.25;

  /** 推过“起始空隙”之外最多再走多少格。 */
  private static final double OVERRUN_BLOCKS = 1.0;

  /** 推了这么多 tick 还几乎没动就算推不动。 */
  private static final int STALL_TICKS = 40;

  /** 推不动的判定：走不到这么多格。 */
  private static final double STALL_BLOCKS = 0.05;

  /** 超时的余量（tick）。 */
  private static final int TIMEOUT_SLACK_TICKS = 60;

  /** 空隙比起始时大出这么多格就是在往反方向推。 */
  private static final double WRONG_WAY_BLOCKS = 0.5;

  /** 看门狗的期限比推车动作自己的超时再宽这么多 tick：动作没在跑也到期收尾。 */
  private static final int WATCHDOG_GRACE_TICKS = 100;

  /** 停车后最多等多少 tick 再去挂（等列车完全停稳，联挂判定才不把它当作在动）。 */
  private static final int SETTLE_ATTEMPTS = 10;

  /** 看门狗间隔（tick）。 */
  private static final long WATCHDOG_TICKS = 10L;

  private static final double TICKS_PER_SECOND = 20.0;

  /** 推车结果。 */
  enum Outcome {
    /** 挂上了。 */
    LINKED,
    /** 推到了，但没挂上（{@link Finished#link()} 写原因）。 */
    LINK_FAILED,
    /** 推过了仍没接上。 */
    OVERRUN,
    /** 推不动或超时。 */
    STALLED,
    /** 列车朝反方向移动。 */
    WRONG_WAY,
    /** 任一方列车不在了，或推车动作被别处清掉。 */
    LOST
  }

  /**
   * 推车收尾。
   *
   * @param outcome 结果
   * @param link 推到后去挂的结果；没推到时为空
   */
  record Finished(Outcome outcome, Optional<CouplerService.LinkAttempt> link) {}

  private final Plugin plugin;
  private final CouplerService service;
  private final Map<MinecartGroup, Push> active = new IdentityHashMap<>();
  private BukkitTask watchdog;

  ShuntPusher(Plugin plugin, CouplerService service) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.service = Objects.requireNonNull(service, "service");
  }

  /** 这列车是否正被推着，或是正被推向的那列。 */
  boolean involved(MinecartGroup group) {
    if (group == null) {
      return false;
    }
    if (active.containsKey(group)) {
      return true;
    }
    for (Push push : active.values()) {
      if (push.target.getGroup() == group) {
        return true;
      }
    }
    return false;
  }

  /**
   * 开始推车。调用方已判定两车都能动、对方这一端车钩开着、节数不超上限。
   *
   * @param mover 被推那列开着车钩的端车
   * @param moverEnd 端车开着车钩的那一端
   * @param target 前方找到的对方
   * @param speedBlocksPerSecond 推车速度
   * @param maxCars 联挂后最多几节
   * @param done 收尾回调（主线程）
   */
  void start(
      MinecartMember<?> mover,
      CouplerState.End moverEnd,
      PushTargetFinder.Target target,
      double speedBlocksPerSecond,
      int maxCars,
      Consumer<Finished> done) {
    MinecartGroup group = mover.getGroup();
    Vector start = CarEnds.center(mover).orElseThrow();
    Vector outward = CarEnds.outward(CarEnds.forward(mover).orElseThrow(), moverEnd);
    double speed = speedBlocksPerSecond / TICKS_PER_SECOND;
    Push push =
        new Push(
            group,
            mover,
            moverEnd,
            target.member(),
            target.end(),
            start,
            target.gap() + OVERRUN_BLOCKS,
            (int) Math.ceil((target.gap() + OVERRUN_BLOCKS) / speed) + TIMEOUT_SLACK_TICKS,
            speed,
            maxCars,
            done);
    push.initialGap =
        start.distance(CarEnds.center(target.member()).orElse(start))
            - CarEnds.coupledDistance(mover, target.member());
    push.startTick = Bukkit.getCurrentTick();
    // 停着的受管列车身上挂着停站留下的等待动作（调用方已确认只有等待动作），不清掉推车动作排不到前面；没挂上时补回。
    push.clearedWaits = group.getActions().hasAction();
    group.getActions().clear();
    push.reversed = alignHead(group, mover, outward);
    TrainProperties properties = group.getProperties();
    push.savedSpeedLimit = properties.getSpeedLimit();
    if (properties.getSpeedLimit() < speed) {
      properties.setSpeedLimit(speed);
    }
    // 被推向的那列也算调车中：推车期间不被发车票接走、不被回收。
    push.names.addAll(service.controlNames(group));
    push.names.addAll(service.controlNames(target.member().getGroup()));
    push.names.forEach(ShuntingRegistry::begin);
    push.action = group.getActions().addAction(new PushAction(push));
    BlockFace heading = mover.getDirection();
    debug(
        "SHUNT_PUSH_START train="
            + service.displayName(group)
            + " target="
            + service.displayName(target.member().getGroup())
            + String.format(Locale.ROOT, " gap=%.2f speed=%.3f", target.gap(), speed)
            + " reversed="
            + push.reversed
            + " moverIsHead="
            + (group.head() == mover)
            + " heading="
            + heading
            + String.format(Locale.ROOT, " outward=(%.2f,%.2f)", outward.getX(), outward.getZ())
            + String.format(
                Locale.ROOT,
                " speedLimit=%.3f->%.3f",
                push.savedSpeedLimit,
                properties.getSpeedLimit()));
    active.put(group, push);
    ensureWatchdog();
  }

  /** 插件停用：所有推车就地停下并收尾（不去挂）。 */
  void stopAll() {
    for (Push push : new ArrayList<>(active.values())) {
      halt(push);
      complete(push, Outcome.LOST);
    }
    if (watchdog != null) {
      watchdog.cancel();
      watchdog = null;
    }
  }

  /** 让开着车钩的端车成为车头、车头朝着要推的方向；调了头时为真。 */
  private static boolean alignHead(MinecartGroup group, MinecartMember<?> mover, Vector outward) {
    boolean reversed = false;
    if (group.size() > 1 && group.head() != mover) {
      group.reverse();
      reversed = true;
    }
    if (group.size() == 1 && headingAgainst(mover, outward)) {
      group.reverse();
      reversed = true;
    }
    return reversed;
  }

  private static boolean headingAgainst(MinecartMember<?> member, Vector outward) {
    BlockFace face = member.getDirection();
    if (face == null) {
      return false;
    }
    Vector heading = new Vector(face.getModX(), face.getModY(), face.getModZ());
    return heading.dot(outward) < 0.0;
  }

  private void ensureWatchdog() {
    if (watchdog == null) {
      watchdog =
          Bukkit.getScheduler().runTaskTimer(plugin, this::watch, WATCHDOG_TICKS, WATCHDOG_TICKS);
    }
  }

  /** 动作被别处清掉、列车被卸载或删掉、或过了期限动作仍没收尾时收尾。 */
  private void watch() {
    int now = Bukkit.getCurrentTick();
    for (Push push : new ArrayList<>(active.values())) {
      if (push.finishing) {
        continue;
      }
      boolean actionGone =
          !push.group.isValid()
              || push.group.isUnloaded()
              || !push.group.getActions().getScheduledActions().contains(push.action);
      boolean overdue = now - push.startTick > push.maxTicks + WATCHDOG_GRACE_TICKS;
      if (actionGone || overdue) {
        halt(push);
        complete(push, actionGone ? Outcome.LOST : Outcome.STALLED);
      }
    }
    if (active.isEmpty() && watchdog != null) {
      watchdog.cancel();
      watchdog = null;
    }
  }

  /** 推车动作每 tick 推进一次；返回下一步。 */
  private Step advance(Push push) {
    push.ticks++;
    MinecartGroup targetGroup = push.target.getGroup();
    if (!push.group.isValid()
        || targetGroup == null
        || !targetGroup.isValid()
        || targetGroup == push.group) {
      return Step.LOST;
    }
    Optional<Vector> moverAt = CarEnds.center(push.mover);
    Optional<Vector> targetAt = CarEnds.center(push.target);
    if (moverAt.isEmpty() || targetAt.isEmpty()) {
      return Step.LOST;
    }
    double gap =
        moverAt.get().distance(targetAt.get()) - CarEnds.coupledDistance(push.mover, push.target);
    if (gap <= LINK_GAP_BLOCKS) {
      return Step.ARRIVED;
    }
    if (gap > push.initialGap + WRONG_WAY_BLOCKS) {
      return Step.WRONG_WAY;
    }
    double travelled = moverAt.get().distance(push.start);
    if (push.ticks == STALL_TICKS / 2) {
      debug(
          "SHUNT_PUSH_PROGRESS train="
              + service.displayName(push.group)
              + " ticks="
              + push.ticks
              + String.format(
                  Locale.ROOT,
                  " travelled=%.2f gap=%.2f velocity=%.4f moving=%s",
                  travelled,
                  gap,
                  push.group.head().getRealSpeed(),
                  push.group.isMoving()));
    }
    if (travelled > push.budget) {
      return Step.OVERRUN;
    }
    if (push.ticks > push.maxTicks || (push.ticks > STALL_TICKS && travelled < STALL_BLOCKS)) {
      return Step.STALLED;
    }
    return Step.CONTINUE;
  }

  private void debug(String message) {
    if (plugin instanceof org.fetarute.fetaruteTCAddon.FetaruteTCAddon addon
        && addon.getLoggerManager() != null) {
      addon.getLoggerManager().debug(message);
    }
  }

  /** 停车（不再施力、速度归零）。 */
  private static void halt(Push push) {
    if (push.group.isValid()) {
      push.group.setForwardForce(0.0);
      push.group.stop();
    }
  }

  /** 推车动作结束：等列车停稳后去挂，或直接收尾。 */
  private void finish(Push push, Step step) {
    push.finishing = true;
    debug(
        "SHUNT_PUSH_END train="
            + service.displayName(push.group)
            + " step="
            + step
            + " ticks="
            + push.ticks
            + String.format(
                Locale.ROOT,
                " travelled=%.2f",
                CarEnds.center(push.mover).map(at -> at.distance(push.start)).orElse(Double.NaN)));
    halt(push);
    settle(push, step, 0);
  }

  private void settle(Push push, Step step, int attempt) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (step != Step.ARRIVED) {
                complete(push, step.outcome());
                return;
              }
              if (push.group.isValid() && push.group.isMoving() && attempt < SETTLE_ATTEMPTS) {
                push.group.stop();
                settle(push, step, attempt + 1);
                return;
              }
              link(push);
            });
  }

  private void link(Push push) {
    restoreControl(push);
    CouplerService.LinkAttempt attempt = service.link(push.mover, push.target, push.maxCars);
    boolean linked =
        attempt
            .result()
            .map(result -> result.outcome() == TrainLinker.Outcome.LINKED)
            .orElse(false);
    if (linked) {
      CouplerState.setOpen(push.mover, push.moverEnd, false);
      CouplerState.setOpen(push.target, push.targetEnd, false);
      active.remove(push.group);
      push.done.accept(new Finished(Outcome.LINKED, Optional.of(attempt)));
      return;
    }
    unwind(push);
    push.done.accept(new Finished(Outcome.LINK_FAILED, Optional.of(attempt)));
  }

  /** 没推到：收尾并调回原朝向。 */
  private void complete(Push push, Outcome outcome) {
    push.finishing = true;
    restoreControl(push);
    unwind(push);
    push.done.accept(new Finished(outcome, Optional.empty()));
  }

  /** 还原限速、撤销调车登记。 */
  private static void restoreControl(Push push) {
    if (push.controlRestored) {
      return;
    }
    push.controlRestored = true;
    if (push.group.isValid()) {
      push.group.getProperties().setSpeedLimit(push.savedSpeedLimit);
    }
    push.names.forEach(ShuntingRegistry::end);
  }

  private void unwind(Push push) {
    active.remove(push.group);
    if (push.group.isValid() && push.reversed) {
      push.group.reverse();
      push.reversed = false;
    }
    if (push.group.isValid() && push.clearedWaits) {
      push.group.getActions().addActionWaitState();
      push.clearedWaits = false;
    }
    if (push.group.isValid()) {
      service.settle(push.group);
    }
  }

  /** 推车动作每 tick 的判定。 */
  private enum Step {
    CONTINUE,
    ARRIVED,
    OVERRUN,
    STALLED,
    WRONG_WAY,
    LOST;

    Outcome outcome() {
      return switch (this) {
        case OVERRUN -> Outcome.OVERRUN;
        case STALLED -> Outcome.STALLED;
        case WRONG_WAY -> Outcome.WRONG_WAY;
        default -> Outcome.LOST;
      };
    }
  }

  /** 一次推车的状态。 */
  private static final class Push {
    final MinecartGroup group;
    final MinecartMember<?> mover;
    final CouplerState.End moverEnd;
    final MinecartMember<?> target;
    final CouplerState.End targetEnd;
    final Vector start;
    final double budget;
    final int maxTicks;
    final double speed;
    final int maxCars;
    final Consumer<Finished> done;
    final Set<String> names = new LinkedHashSet<>();
    GroupAction action;
    boolean reversed;
    boolean clearedWaits;
    double initialGap;
    int startTick;
    double savedSpeedLimit;
    int ticks;
    boolean finishing;
    boolean controlRestored;

    Push(
        MinecartGroup group,
        MinecartMember<?> mover,
        CouplerState.End moverEnd,
        MinecartMember<?> target,
        CouplerState.End targetEnd,
        Vector start,
        double budget,
        int maxTicks,
        double speed,
        int maxCars,
        Consumer<Finished> done) {
      this.group = group;
      this.mover = mover;
      this.moverEnd = moverEnd;
      this.target = target;
      this.targetEnd = targetEnd;
      this.start = start;
      this.budget = budget;
      this.maxTicks = maxTicks;
      this.speed = speed;
      this.maxCars = maxCars;
      this.done = done;
    }
  }

  /** 推车动作：每 tick 判一次到没到，同一 tick 的各小步施加同一前进力。 */
  private final class PushAction extends GroupAction {

    private final Push push;
    private int advancedTick = -1;
    private Step step = Step.CONTINUE;

    PushAction(Push push) {
      this.push = push;
    }

    @Override
    public boolean update() {
      if (push.finishing) {
        return true;
      }
      int tick = elapsedTicks();
      if (tick != advancedTick) {
        advancedTick = tick;
        step = advance(push);
      }
      if (step != Step.CONTINUE) {
        finish(push, step);
        return true;
      }
      getGroup().setForwardForce(push.speed / Math.max(1, getGroup().getUpdateStepCount()));
      return false;
    }
  }
}
