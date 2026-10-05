package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次编表的进度：编表线程写，主线程随时读。
 *
 * <p>编表的耗时几乎全在完整构建（{@code attempt}：派车、串行、让车修复、冲突扫描）的次数上——目标间隔排不开时放宽搜索、
 * 逐组收紧、快车错峰的每个候选都要完整构建一次。所以除了阶段， 还报完整构建了几次：次数在涨就是在干活，不是卡死。
 *
 * <p>只记不算：不知道总共要编几次（搜索会提前收手），不给百分比。
 *
 * <p>取消是协作式的：每次完整构建开始前查一次（{@link #checkpoint}），被取消就抛 {@link Cancelled}，最多等完当前这一次构建。 编完交给落库（{@link
 * #beginSaving}）之后就取消不了：取消与开始保存只有一个能成，不会出现"说了不保存却存上了"。
 */
public final class TimetableBuildProgress {

  /** 编表阶段，按发生顺序。 */
  public enum Stage {
    QUEUED("排队"),
    NEIGHBORS("读取邻表"),
    PREPARE("计算时分"),
    TARGET("按目标间隔编排"),
    RELAX("放宽间隔搜索"),
    TIGHTEN("逐组收紧"),
    STAGGER("快车错峰搜索"),
    PLATFORMS("排计划站台与量瓶颈"),
    REPORT("汇报与保存");

    private final String label;

    Stage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 某一时刻的进度。
   *
   * @param stage 当前阶段
   * @param detail 阶段内正在试什么（如“试 156s”）；没有时为空串
   * @param fullBuilds 已完整构建的次数（含错峰候选）
   * @param elapsed 从开始编表起的用时
   * @param stageElapsed 进入当前阶段后的用时
   */
  public record Snapshot(
      Stage stage, String detail, int fullBuilds, Duration elapsed, Duration stageElapsed) {}

  /** 编表被取消：从编表线程一路抛到发起处，不当作编表失败。 */
  public static final class Cancelled extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Cancelled() {
      super("编表已取消");
    }
  }

  /** 编表的去向：还在编、已取消、已交给落库。后两者互斥，谁先到算谁的。 */
  private enum State {
    RUNNING,
    CANCELLED,
    SAVING
  }

  private final Clock clock;
  private final Instant startedAt;
  private final AtomicInteger fullBuilds = new AtomicInteger();
  private volatile Stage stage = Stage.QUEUED;
  private volatile String detail = "";
  private volatile Instant stageStartedAt;
  private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

  public TimetableBuildProgress() {
    this(Clock.systemUTC());
  }

  public TimetableBuildProgress(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.startedAt = clock.instant();
    this.stageStartedAt = startedAt;
  }

  /** 不需要进度时用的一份：照常记，没人读。 */
  public static TimetableBuildProgress untracked() {
    return new TimetableBuildProgress();
  }

  /** 进入新阶段，清空阶段内说明；同一阶段重复进入不重置阶段计时。 */
  public void stage(Stage next) {
    Objects.requireNonNull(next, "next");
    if (next != stage) {
      stage = next;
      stageStartedAt = clock.instant();
    }
    detail = "";
  }

  /** 阶段内正在试什么。 */
  public void detail(String text) {
    detail = text == null ? "" : text;
  }

  /** 开始一次完整构建；已被取消时抛 {@link Cancelled}。 */
  public void fullBuild() {
    checkpoint();
    fullBuilds.incrementAndGet();
  }

  /**
   * 请求取消：编表线程在下一个检查点停下。
   *
   * @return 取消成功（含此前已取消）为 true；已开始保存、取消不了时为 false
   */
  public boolean cancel() {
    state.compareAndSet(State.RUNNING, State.CANCELLED);
    return state.get() == State.CANCELLED;
  }

  public boolean cancelled() {
    return state.get() == State.CANCELLED;
  }

  /** 已交给落库，取消不了。 */
  public boolean saving() {
    return state.get() == State.SAVING;
  }

  /**
   * 编完、开始保存：此后取消不了。
   *
   * @return 已被取消时为 false，这份结果不能落库
   */
  public boolean beginSaving() {
    state.compareAndSet(State.RUNNING, State.SAVING);
    return state.get() == State.SAVING;
  }

  /** 已被取消时抛 {@link Cancelled}。 */
  public void checkpoint() {
    if (cancelled()) {
      throw new Cancelled();
    }
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Snapshot snapshot() {
    Instant now = clock.instant();
    Stage currentStage = stage;
    return new Snapshot(
        currentStage,
        detail,
        fullBuilds.get(),
        nonNegative(Duration.between(startedAt, now)),
        nonNegative(Duration.between(stageStartedAt, now)));
  }

  private static Duration nonNegative(Duration duration) {
    return duration.isNegative() ? Duration.ZERO : duration;
  }
}
