package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CouplingOperations;
import org.fetarute.fetaruteTCAddon.drive.session.TrainPropertyGuard;

/**
 * 在指定处把一列车拆成两列。
 *
 * <p>含原车头车的一半（TrainCarts 的源编组）保留原车的身份；拆下的一半成为一列独立的车。受管列车按插件自己发起的拆分走（运行时监听不当事故处理）， 拆下的一半换新车名与标签（见
 * {@link SplitTagPlan}），两半下一 tick 都按现场车身重新占用；拆下的一半交路与进度照搬原车，停在终点的会照常登记为待命车。 非受管列车照 TrainCarts
 * 原样拆。只在服务器主线程使用。
 */
public final class TrainSplitter {

  /** 新车名撞名时最多换几次运行 ID 重取。 */
  private static final int NAME_ATTEMPTS = 8;

  /** 拆分结果。 */
  public enum Outcome {
    /** 拆开了。 */
    SPLIT,
    /** 列车无效或拆分位置不在两节车之间。 */
    INVALID,
    /** TrainCarts 没有拆开。 */
    NOT_SPLIT,
    /** 拆开了，但拆下的一半取不到可用的新车名：仍带着原车身份，调度会按重复列车处理它。 */
    RENAME_FAILED
  }

  /**
   * @param outcome 结果
   * @param kept 保留原身份的一半
   * @param detached 拆下的一半；没拆开时为空
   * @param detachedName 拆下的一半的车名；没拆开时为空
   */
  public record Result(
      Outcome outcome,
      Optional<MinecartGroup> kept,
      Optional<MinecartGroup> detached,
      Optional<String> detachedName) {

    static Result of(Outcome outcome) {
      return new Result(outcome, Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  private final RuntimeDispatchService dispatch;
  private final Consumer<Runnable> nextTick;
  private final Predicate<String> nameTaken;
  private final Consumer<String> debug;

  /**
   * @param dispatch 运行时调度
   * @param nextTick 下一 tick 执行
   * @param debug 调试日志
   */
  public TrainSplitter(
      RuntimeDispatchService dispatch, Consumer<Runnable> nextTick, Consumer<String> debug) {
    this(dispatch, nextTick, TrainPropertiesStore::exists, debug);
  }

  TrainSplitter(
      RuntimeDispatchService dispatch,
      Consumer<Runnable> nextTick,
      Predicate<String> nameTaken,
      Consumer<String> debug) {
    this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    this.nextTick = Objects.requireNonNull(nextTick, "nextTick");
    this.nameTaken = Objects.requireNonNull(nameTaken, "nameTaken");
    this.debug = debug == null ? message -> {} : debug;
  }

  /**
   * 在第 {@code at} 节车之前拆开：第 0..at-1 节保留原身份，第 at 节起拆下。
   *
   * @param group 列车
   * @param at 拆下部分第一节在编组里的下标（1..size-1）
   */
  public Result split(MinecartGroup group, int at) {
    if (group == null || !group.isValid() || at <= 0 || at >= group.size()) {
      return Result.of(Outcome.INVALID);
    }
    TrainProperties properties = group.getProperties();
    boolean managed = properties != null && dispatch.hasFtaRuntimeTag(properties);
    String sourceName =
        properties == null
            ? ""
            : managed
                ? dispatch.resolveTrackedTrainName(properties).orElse(properties.getTrainName())
                : properties.getTrainName();
    MinecartGroup detached;
    CouplingOperations operations = dispatch.coupling().operations();
    try (CouplingOperations.Scope ignored = operations.begin(List.of(group))) {
      detached = group.split(at);
    }
    if (detached == null || detached == group || !detached.isValid()) {
      return Result.of(Outcome.NOT_SPLIT);
    }
    // 拆下的一半照搬了原车的列车属性：有人驾驶时那是驾驶期间改过的（不减速、限速抬高），按留存的原值还原
    TrainPropertyGuard.restore(detached.getProperties());
    if (!managed) {
      return new Result(
          Outcome.SPLIT,
          Optional.of(group),
          Optional.of(detached),
          Optional.ofNullable(detached.getProperties().getTrainName()));
    }
    Optional<String> name = renameDetached(detached, sourceName);
    if (name.isEmpty()) {
      debug.accept("COUPLER_SPLIT_RENAME_FAILED source=" + sourceName);
      return new Result(
          Outcome.RENAME_FAILED, Optional.of(group), Optional.of(detached), Optional.empty());
    }
    dispatch.coupling().requestPhysicalRehydration(sourceName);
    nextTick.accept(
        () -> {
          if (group.isValid()) {
            dispatch.refreshSignal(group);
          }
          if (detached.isValid()) {
            dispatch.refreshSignal(detached);
          }
        });
    debug.accept(
        "COUPLER_SPLIT source="
            + sourceName
            + " kept="
            + group.size()
            + " detached="
            + name.get()
            + " cars="
            + detached.size());
    return new Result(Outcome.SPLIT, Optional.of(group), Optional.of(detached), name);
  }

  /**
   * 在第 {@code at} 节车之前拆开，但让后一半（第 at 节起）保留原身份、前一半拆下：解编切车头时用。
   *
   * <p>先调头、按调头后的下标拆，再把两半各调回来。两半都停着，调头只换车头与运行方向，车厢朝向不变。
   *
   * @param group 列车（须已停稳）
   * @param at 后一半第一节在编组里的下标（1..size-1）
   */
  public Result splitKeepingRear(MinecartGroup group, int at) {
    if (group == null || !group.isValid() || at <= 0 || at >= group.size()) {
      return Result.of(Outcome.INVALID);
    }
    int size = group.size();
    group.reverse();
    Result result;
    try {
      result = split(group, size - at);
    } finally {
      if (group.isValid()) {
        group.reverse();
      }
    }
    result.detached().filter(MinecartGroup::isValid).ifPresent(MinecartGroup::reverse);
    return result;
  }

  /** 给拆下的一半取新车名、改 TrainCarts 车名并换上新身份的标签；取不到可用车名时为空。 */
  private Optional<String> renameDetached(MinecartGroup detached, String sourceName) {
    TrainProperties properties = detached.getProperties();
    for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
      UUID runId = UUID.randomUUID();
      String name = SplitTagPlan.detachedName(sourceName, runId);
      if (nameTaken.test(name)) {
        continue;
      }
      try {
        properties.setTrainName(name);
      } catch (IllegalArgumentException ex) {
        continue;
      }
      SplitTagPlan.forDetached(name, runId).applyTo(properties);
      return Optional.of(name);
    }
    return Optional.empty();
  }
}
