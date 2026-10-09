package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;

/**
 * 叫来的车按需降速：平时全速，追近前车时把线路限速压到刚好不再追近，免得被信号拦停、走走停停。
 *
 * <p>前车间隔按信号前瞻量出的阻塞距离与本车车速折成秒（{@code 距离 / 车速}）；不到跟车间隔时，线路限速乘以 {@code 间隔 / 跟车间隔}， 最低 {@link
 * #MIN_FACTOR}。再近就交给信号。后车追近时不降，免得把后面的表定车也拖慢：一辆车的前瞻里阻塞它的是叫来的车、折算间隔又不到 {@link
 * Settings#rearLeadSeconds()}；或者按到站预计判出身后有车追近（{@link #setRearCheck}，补前瞻窗口看不到的远处后车）。
 *
 * <p>倍率由每个信号 tick 观测（{@link #observe}）、下一个 tick 起作用（{@link #factor}）；只降不升，进站限速、临时限速、CAUTION
 * 与信号速度照常生效。只在服务器主线程调用。
 */
public final class CalledTrainPacer {

  /** 降速最多降到线路限速的这个比例；再近交给信号。 */
  static final double MIN_FACTOR = 0.7D;

  /** 车速低于此值（格/秒）时不按车速折算间隔：刚起步或停着的车折出来的间隔没有意义。 */
  static final double MIN_SPEED_BPS = 1.0D;

  /** 后车折算间隔时车速的下限：后车停着（多半正被叫来的车挡着）也算它近在身后。 */
  static final double REAR_SPEED_FLOOR_BPS = 4.0D;

  /** 后车追近的判定保留多久：后车每个 tick 都会重新观测。 */
  static final Duration REAR_HOLD = Duration.ofSeconds(3);

  /** 倍率多久没更新就作废（车没了、不再经过前瞻）。 */
  static final Duration FACTOR_TTL = Duration.ofSeconds(10);

  private static final int PRUNE_THRESHOLD = 128;

  /**
   * 降速参数。
   *
   * @param followGapSeconds 跟车间隔：离前车不到这么多秒才降速；非正值表示不降速
   * @param rearLeadSeconds 后车离叫来的车不到这么多秒时不降速
   */
  public record Settings(int followGapSeconds, int rearLeadSeconds) {

    /** 不降速。 */
    public static final Settings DISABLED = new Settings(0, 0);

    public Settings {
      followGapSeconds = Math.max(0, followGapSeconds);
      rearLeadSeconds = Math.max(0, rearLeadSeconds);
    }

    boolean enabled() {
      return followGapSeconds > 0;
    }
  }

  private record Pace(double factor, Instant at) {}

  private final Consumer<String> debugLogger;
  private final Supplier<Instant> clock;
  private volatile Settings settings = Settings.DISABLED;
  private volatile Predicate<String> rearCheck = trainName -> false;
  private final Map<String, Pace> paces = new ConcurrentHashMap<>();
  private final Map<String, Instant> rearCloseUntil = new ConcurrentHashMap<>();

  CalledTrainPacer(Consumer<String> debugLogger, Supplier<Instant> clock) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    this.clock = clock == null ? Instant::now : clock;
  }

  /** 装上“身后有车追近”的判定；{@code null} 表示只看前瞻。 */
  void setRearCheck(Predicate<String> next) {
    this.rearCheck = next == null ? trainName -> false : next;
  }

  /** 换参数；{@code null} 表示不降速。 */
  void setSettings(Settings next) {
    this.settings = next == null ? Settings.DISABLED : next;
    if (!this.settings.enabled()) {
      paces.clear();
      rearCloseUntil.clear();
    }
  }

  /**
   * 一个信号 tick 的观测：记下这辆车身后紧跟着的叫来的车（后车追近），叫来的车自己按前车间隔算降速倍率。
   *
   * @param trainName 列车名
   * @param properties 列车属性（认叫车标签）
   * @param decision 前瞻用的占用判定（阻塞者是谁）
   * @param blockerDistance 到首个阻塞资源的距离；前瞻没有阻塞时为空
   * @param speedBps 本车车速（格/秒）
   * @param now 当前时刻
   */
  void observe(
      String trainName,
      TrainProperties properties,
      OccupancyDecision decision,
      OptionalLong blockerDistance,
      double speedBps,
      Instant now) {
    Settings current = this.settings;
    String key = keyOf(trainName);
    if (!current.enabled() || key == null || now == null) {
      return;
    }
    if (paces.size() > PRUNE_THRESHOLD || rearCloseUntil.size() > PRUNE_THRESHOLD) {
      prune(now);
    }
    markRear(trainName, decision, blockerDistance, speedBps, current, now);
    boolean called =
        properties != null
            && TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_CALLED_TRAIN)
                .isPresent();
    if (!called) {
      paces.remove(key);
      return;
    }
    Instant rearUntil = rearCloseUntil.get(key);
    boolean rearClose = rearUntil != null && now.isBefore(rearUntil) || rearClose(trainName);
    double factor =
        rearClose ? 1.0D : factorFor(blockerDistance, speedBps, current.followGapSeconds());
    Pace previous = factor >= 1.0D ? paces.remove(key) : paces.put(key, new Pace(factor, now));
    boolean wasPaced = previous != null && previous.factor() < 1.0D;
    if (wasPaced != factor < 1.0D) {
      debugLogger.accept(
          "CALL_PACING train="
              + trainName
              + " state="
              + (factor < 1.0D ? "engaged" : "released")
              + " factor="
              + String.format(java.util.Locale.ROOT, "%.2f", factor)
              + " blockerDistance="
              + (blockerDistance.isPresent() ? blockerDistance.getAsLong() : "-")
              + " speedBps="
              + String.format(java.util.Locale.ROOT, "%.1f", speedBps)
              + " rearClose="
              + rearClose);
    }
  }

  /** 按到站预计判出的“身后有车追近”；判定出错时当作追近（不降速）。 */
  private boolean rearClose(String trainName) {
    try {
      return rearCheck.test(trainName);
    } catch (RuntimeException ex) {
      debugLogger.accept("CALL_PACING_REAR_CHECK_FAILED train=" + trainName + " error=" + ex);
      return true;
    }
  }

  /**
   * 线路限速倍率：没在降速时为 1。
   *
   * @param trainName 列车名
   */
  public double factor(String trainName) {
    String key = keyOf(trainName);
    if (key == null || !settings.enabled()) {
      return 1.0D;
    }
    Pace pace = paces.get(key);
    if (pace == null) {
      return 1.0D;
    }
    Instant now = clock.get();
    if (now.isAfter(pace.at().plus(FACTOR_TTL))) {
      paces.remove(key, pace);
      return 1.0D;
    }
    return pace.factor();
  }

  /**
   * 按前车间隔算降速倍率：不到跟车间隔时为 {@code 间隔 / 跟车间隔}，最低 {@link #MIN_FACTOR}；没有阻塞、车速太低或间隔够时为 1。
   *
   * @param blockerDistance 到首个阻塞资源的距离
   * @param speedBps 本车车速（格/秒）
   * @param followGapSeconds 跟车间隔
   */
  static double factorFor(OptionalLong blockerDistance, double speedBps, int followGapSeconds) {
    if (followGapSeconds <= 0
        || blockerDistance == null
        || blockerDistance.isEmpty()
        || !(speedBps >= MIN_SPEED_BPS)) {
      return 1.0D;
    }
    double gapSeconds = Math.max(0L, blockerDistance.getAsLong()) / speedBps;
    if (gapSeconds >= followGapSeconds) {
      return 1.0D;
    }
    return Math.max(MIN_FACTOR, gapSeconds / followGapSeconds);
  }

  /** 这辆车被别的车挡着、折算间隔又不到后车追近的门槛：挡它的车都记为“后车追近”。 */
  private void markRear(
      String trainName,
      OccupancyDecision decision,
      OptionalLong blockerDistance,
      double speedBps,
      Settings current,
      Instant now) {
    if (decision == null
        || decision.blockers() == null
        || decision.blockers().isEmpty()
        || blockerDistance == null
        || blockerDistance.isEmpty()
        || current.rearLeadSeconds() <= 0) {
      return;
    }
    double speed = Math.max(REAR_SPEED_FLOOR_BPS, Double.isFinite(speedBps) ? speedBps : 0.0D);
    double gapSeconds = Math.max(0L, blockerDistance.getAsLong()) / speed;
    if (gapSeconds >= current.rearLeadSeconds()) {
      return;
    }
    Instant until = now.plus(REAR_HOLD);
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      String owner = keyOf(claim.trainName());
      if (owner != null) {
        rearCloseUntil.put(owner, until);
      }
    }
  }

  private void prune(Instant now) {
    Instant staleBefore = now.minus(FACTOR_TTL);
    paces.values().removeIf(pace -> pace.at().isBefore(staleBefore));
    rearCloseUntil.values().removeIf(until -> until.isBefore(now));
  }

  private static String keyOf(String trainName) {
    if (trainName == null) {
      return null;
    }
    String key = TrainNameNormalizer.normalizeKey(trainName);
    return key.isEmpty() ? null : key;
  }
}
