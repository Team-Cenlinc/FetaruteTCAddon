package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一段走行的运行曲线：列车在速度天花板下按 {@link SpeedCurve} 起步与制动，逐节点给出到达时刻。
 *
 * <p>天花板（{@link SpeedCeiling}）由每条边的限速、额外限速区（进站限速区）与终点速度按制动曲线往回推得，保证任何一处都来得及降到前方限速；
 * 再在它下面按加速曲线正向推，就是"能多快就多快，但在任何一处限速之前都来得及刹住"的速度曲线。
 *
 * <p>运行时控车用的是同一个天花板（交给逐 tick 限速斜坡跟随）与同一条加速曲线（自带的 launch 动作逐 tick 推速），两者是同一条速度曲线； 剩余偏差来自 TrainCarts
 * 的执行（截速步长、居中刹停等）与调度周期的节拍。
 *
 * <p>每段加速都从当时的速度起算起步渐增：发车、驶过慢速边后重新提速都是新的一段；贴着天花板巡航或制动时不算加速。 相邻两个采样点之间按匀加减速取平均速度（{@code 2·Δs / (v₁ +
 * v₂)}）。
 */
final class RunCurve {

  /** 判定"贴着天花板"的容差（格/秒）。 */
  private static final double AT_CEILING_TOLERANCE_BPS = 1.0e-6;

  /** {@link #CACHE} 的条目上限，按最近使用淘汰。 */
  private static final int CACHE_LIMIT = 1024;

  /**
   * 输入相同时复用上次的到达秒数。
   *
   * <p>站牌、时刻表 API 与地图每次刷新都要给每趟车、每张票算到目标的运行曲线。途中停车点之后的各段都从静止起步、节点固定，
   * 未发车票据整条路径都不变，每次刷新输入完全相同；逐采样点积分却要按里程走一遍。结果只取决于输入，可在线程间共享。
   *
   * <p>运行中列车的首段（当前速度、首边剩余长度）每次都不同，编表也会塞进大量一次性的输入；按最近使用淘汰才留得住反复命中的那些段。
   */
  private static final Map<CacheKey, double[]> CACHE =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, double[]> eldest) {
              return size() > CACHE_LIMIT;
            }
          });

  private RunCurve() {}

  /**
   * 计算到达每个节点的秒数。
   *
   * @param lengths 各边长度（格），均为正；按 {@link SpeedCeiling#quantize} 落到网格上（整数格不受影响，列车正处在边中间时首边是剩余长度）
   * @param speeds 各边限速（格/秒），均为正
   * @param caps 额外限速区，可为空
   * @param entrySpeed 起点速度（停车点起步为 0），超过首边限速时按首边限速
   * @param exitSpeed 终点速度上限（进站限速或 0）；不设上限传 {@link Double#POSITIVE_INFINITY}
   * @param curve 加减速曲线
   * @return 长度为 {@code 边数 + 1} 的到达秒数，首项为 0
   */
  static double[] nodeTimes(
      double[] lengths,
      double[] speeds,
      List<SpeedCeiling.Cap> caps,
      double entrySpeed,
      double exitSpeed,
      SpeedCurve curve) {
    Objects.requireNonNull(lengths, "lengths");
    Objects.requireNonNull(speeds, "speeds");
    Objects.requireNonNull(curve, "curve");
    if (lengths.length != speeds.length) {
      throw new IllegalArgumentException("lengths 与 speeds 数量不匹配");
    }
    CacheKey key = new CacheKey(lengths, speeds, caps, entrySpeed, exitSpeed, curve);
    double[] hit = CACHE.get(key);
    if (hit != null) {
      return hit.clone();
    }
    double[] computed = computeNodeTimes(lengths, speeds, caps, entrySpeed, exitSpeed, curve);
    CACHE.put(key, computed.clone());
    return computed;
  }

  private static double[] computeNodeTimes(
      double[] lengths,
      double[] speeds,
      List<SpeedCeiling.Cap> caps,
      double entrySpeed,
      double exitSpeed,
      SpeedCurve curve) {
    int edges = lengths.length;
    int[] nodeStep = SpeedCeiling.nodeSteps(lengths);
    double[] times = new double[edges + 1];
    if (nodeStep[edges] == 0) {
      return times;
    }
    SpeedCeiling ceiling = SpeedCeiling.of(lengths, speeds, caps, exitSpeed, curve);
    double[] seconds = profile(ceiling, entrySpeed, curve).seconds();
    for (int k = 1; k <= edges; k++) {
      times[k] = seconds[nodeStep[k]];
    }
    return times;
  }

  /**
   * 逐点速度与到达秒数。
   *
   * @param speed 各采样点的速度（格/秒）
   * @param seconds 到达各采样点的累计秒数，首项为 0
   */
  record Motion(double[] speed, double[] seconds) {}

  /**
   * 在天花板下按加速曲线正向推出逐点速度与到达秒数。
   *
   * <p>每步按 {@code v² + 2·a·Δs} 提速、不超过天花板（采样点之间按 v² 线性插值，与控车读天花板的方式相同）；
   * 加速度取半步处速度、本段加速的起点速度与下一点天花板下的值（中点法）。 低速时一步要走一秒多、起步段加速度在步内变化明显，把一步再细分成若干小步积分，用时按小步累加。
   *
   * <p>上一点贴着天花板、下一点天花板又高于当前速度时，是新的一段加速，以当前速度为起点；首段按从静止起步计（带速进入时起步段已走过）。
   */
  static Motion profile(SpeedCeiling ceiling, double entrySpeed, SpeedCurve curve) {
    int samples = ceiling.samples();
    double[] speed = new double[samples];
    double[] seconds = new double[samples];
    double velocity = Math.min(ceiling.speedAt(0), Math.max(0.0, entrySpeed));
    speed[0] = velocity;
    // 带速进入（ETA 从运行中位置起算）时，列车这一段加速是从停车点起步的，起步段早已走过：按从静止起步计，
    // 与控车发车动作的同一段加速一致；贴着天花板进入时下面的判定会在天花板回升处另起一段。
    double phaseStart = 0.0;
    for (int i = 1; i < samples; i++) {
      double from = ceiling.speedAt(i - 1);
      double to = ceiling.speedAt(i);
      if (velocity >= from - AT_CEILING_TOLERANCE_BPS && to > velocity) {
        phaseStart = velocity;
      }
      int subSteps = subSteps(velocity);
      double ds = SpeedCeiling.STEP_BLOCKS / subSteps;
      // 首末都要求静止、整段只有一步时两点之和会是 0，按一小步能达到的速度兜底。
      double floor =
          Math.sqrt(
              2.0 * Math.min(curve.accelBps2(), curve.decelBps2()) * SpeedCurve.END_FLOOR * ds);
      double elapsed = 0.0;
      for (int k = 1; k <= subSteps; k++) {
        // 按 v² 插值：制动曲线上 v² 随里程线性变化，按速度线性插值会在刹停前的最后一格把耗时高估近一半。
        double cap = Math.sqrt(from * from + (to * to - from * from) * k / subSteps);
        double half =
            Math.sqrt(velocity * velocity + curve.accelerationBps2(velocity, phaseStart, to) * ds);
        double accel = curve.accelerationBps2(half, phaseStart, to);
        double next = Math.min(cap, Math.sqrt(velocity * velocity + 2.0 * accel * ds));
        elapsed += 2.0 * ds / Math.max(velocity + next, floor);
        velocity = next;
      }
      speed[i] = velocity;
      seconds[i] = seconds[i - 1] + elapsed;
    }
    return new Motion(speed, seconds);
  }

  /** 低速时的细分步数：每小步不超过约 1/16 秒，最多 32 步；4 格/秒以上不细分。 */
  private static int subSteps(double speedBps) {
    return (int) Math.min(32.0, Math.max(1.0, Math.ceil(4.0 / Math.max(speedBps, 0.125))));
  }

  /**
   * {@link #CACHE} 的键：全部输入按值比较；数组在构造时复制，调用方之后改数组不影响键。
   *
   * <p>限速区里的 {@code null} 与计算时一样略过。
   */
  private static final class CacheKey {
    private final double[] lengths;
    private final double[] speeds;
    private final List<SpeedCeiling.Cap> caps;
    private final double entrySpeed;
    private final double exitSpeed;
    private final SpeedCurve curve;
    private final int hash;

    private CacheKey(
        double[] lengths,
        double[] speeds,
        List<SpeedCeiling.Cap> caps,
        double entrySpeed,
        double exitSpeed,
        SpeedCurve curve) {
      this.lengths = lengths.clone();
      this.speeds = speeds.clone();
      this.caps = caps == null ? List.of() : caps.stream().filter(Objects::nonNull).toList();
      this.entrySpeed = entrySpeed;
      this.exitSpeed = exitSpeed;
      this.curve = curve;
      this.hash =
          Objects.hash(
              Arrays.hashCode(this.lengths),
              Arrays.hashCode(this.speeds),
              this.caps,
              entrySpeed,
              exitSpeed,
              curve);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CacheKey key
          && Double.compare(entrySpeed, key.entrySpeed) == 0
          && Double.compare(exitSpeed, key.exitSpeed) == 0
          && Arrays.equals(lengths, key.lengths)
          && Arrays.equals(speeds, key.speeds)
          && caps.equals(key.caps)
          && curve.equals(key.curve);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
