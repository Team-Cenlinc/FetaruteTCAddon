package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一段路径上的速度天花板：任何一处都来得及按 {@link SpeedCurve} 的制动曲线降到前方每一处限速。
 *
 * <p>编表运行曲线（{@link RunCurve}）在它下面按加速曲线正向推算；运行时控车把它交给逐 tick 限速斜坡跟随。两边调的是同一个函数，
 * 输入同一组限速（各边限速、进站限速区、终点速度），制动曲线因此逐点一致。
 *
 * <p>沿里程按 {@link #STEPS_PER_BLOCK} 等分离散，从终点往回推：每一步按当前速度、所在区段限速（巡航速度）与要减到的速度取减速度，
 * 撞上更低的区段限速时以它为新的末速度。制动开始与结束两端都柔和（见 {@link SpeedCurve}）。
 *
 * <p>同时记下每一点受的是哪类约束：额外限速区与终点速度（进站），还是边限速，供控车诊断说明限速来源，不必再算第二份天花板。
 */
public final class SpeedCeiling {

  /** 每格的离散步数：0.25 格一步，时分误差远小于 0.1 秒。 */
  public static final int STEPS_PER_BLOCK = 4;

  /** 每步的里程（格）。 */
  public static final double STEP_BLOCKS = 1.0 / STEPS_PER_BLOCK;

  /** 缓存条目上限；超过即整体清空。控车每列车一条，远大于实服同时在线的列车数。 */
  private static final int CACHE_LIMIT = 256;

  private static final Map<CacheKey, SpeedCeiling> CACHE = new ConcurrentHashMap<>();

  private final double[] speeds;
  private final boolean[] capBound;

  private SpeedCeiling(double[] speeds, boolean[] capBound) {
    this.speeds = speeds;
    this.capBound = capBound;
  }

  /**
   * 额外限速区：里程 {@code [fromBlocks, toBlocks]} 内速度不超过 {@code speedBps}。
   *
   * @param fromBlocks 起点里程（格，相对本段起点）
   * @param toBlocks 终点里程（格，相对本段起点）
   * @param speedBps 限速（格/秒），必须为正
   */
  public record Cap(double fromBlocks, double toBlocks, double speedBps) {
    public Cap {
      if (!Double.isFinite(speedBps) || speedBps <= 0.0) {
        throw new IllegalArgumentException("speedBps 必须为正数");
      }
    }
  }

  /**
   * 计算一段路径的速度天花板。
   *
   * @param lengths 各边长度（格），均为正；按 {@link #quantize} 落到网格上
   * @param speeds 各边限速（格/秒），均为正
   * @param caps 额外限速区，可为空
   * @param exitSpeed 终点速度上限；不设上限传 {@link Double#POSITIVE_INFINITY}
   * @param curve 加减速曲线；为 {@code null} 时不按制动曲线提前降速（只取逐点限速）
   */
  public static SpeedCeiling of(
      double[] lengths, double[] speeds, List<Cap> caps, double exitSpeed, SpeedCurve curve) {
    Limits limits = limits(nodeSteps(lengths), speeds, caps, exitSpeed);
    return curve == null
        ? new SpeedCeiling(limits.speeds(), limits.fromCap())
        : brake(limits, curve);
  }

  /**
   * 同 {@link #of}，输入相同时复用上次的结果。
   *
   * <p>运行时控车每个信号 tick 都要从当前图节点到下一停车点求一次天花板，列车在同一条边上时输入不变，没必要每 tick 重算整段。 结果不可变，可在线程间共享。
   */
  public static SpeedCeiling cached(
      double[] lengths, double[] speeds, List<Cap> caps, double exitSpeed, SpeedCurve curve) {
    CacheKey key =
        new CacheKey(
            boxed(lengths),
            boxed(speeds),
            caps == null ? List.of() : List.copyOf(caps),
            exitSpeed,
            curve);
    SpeedCeiling hit = CACHE.get(key);
    if (hit != null) {
      return hit;
    }
    SpeedCeiling computed = of(lengths, speeds, caps, exitSpeed, curve);
    if (CACHE.size() >= CACHE_LIMIT) {
      CACHE.clear();
    }
    CACHE.put(key, computed);
    return computed;
  }

  /** 采样点数（边数步数之和加 1）。 */
  public int samples() {
    return speeds.length;
  }

  /** 第 {@code index} 个采样点（里程 {@code index / STEPS_PER_BLOCK} 格）的天花板速度。 */
  public double speedAt(int index) {
    return speeds[index];
  }

  /**
   * 里程 {@code distanceBlocks} 处的天花板速度，采样点之间按 v² 线性插值（制动曲线上 v² 随里程线性变化）；超出终点取终点值。
   *
   * @param distanceBlocks 相对本段起点的里程（格）
   */
  public double limitBps(double distanceBlocks) {
    if (speeds.length == 0) {
      return Double.POSITIVE_INFINITY;
    }
    double position = Math.max(0.0, distanceBlocks) * STEPS_PER_BLOCK;
    int index = (int) Math.floor(position);
    if (index >= speeds.length - 1) {
      return speeds[speeds.length - 1];
    }
    double fraction = position - index;
    double from = speeds[index];
    double to = speeds[index + 1];
    return Math.sqrt(from * from + (to * to - from * from) * fraction);
  }

  /**
   * 里程 {@code distanceBlocks} 处的天花板是否由额外限速区或终点速度（进站）决定，而不是由边限速决定。
   *
   * @param distanceBlocks 相对本段起点的里程（格）
   */
  public boolean capBoundAt(double distanceBlocks) {
    if (capBound.length == 0) {
      return false;
    }
    int index = (int) Math.floor(Math.max(0.0, distanceBlocks) * STEPS_PER_BLOCK);
    return capBound[Math.min(index, capBound.length - 1)];
  }

  /**
   * 逐点限速。
   *
   * @param speeds 各采样点的限速
   * @param fromCap 该点限速是否由额外限速区或终点速度压低（低于边限速）
   */
  record Limits(double[] speeds, boolean[] fromCap) {}

  /**
   * 把长度落到离散网格上：四舍五入到 {@code 1 / STEPS_PER_BLOCK} 格，至少一步。
   *
   * <p>调用方计算节点里程（例如进站限速区的起点）时必须用同一个值，否则限速区会与节点错开一两步。
   */
  public static double quantize(double lengthBlocks) {
    return steps(lengthBlocks) * STEP_BLOCKS;
  }

  /** 各节点所在的采样序号，首节点为 0。 */
  static int[] nodeSteps(double[] lengths) {
    Objects.requireNonNull(lengths, "lengths");
    int[] nodeStep = new int[lengths.length + 1];
    for (int k = 0; k < lengths.length; k++) {
      if (!(lengths[k] > 0.0) || !Double.isFinite(lengths[k])) {
        throw new IllegalArgumentException("第 " + k + " 条边的长度必须为正");
      }
      nodeStep[k + 1] = nodeStep[k] + steps(lengths[k]);
    }
    return nodeStep;
  }

  /** 逐点限速：边内取本边限速，节点处取两侧较小者（进下一条边之前必须已经降到它的限速），再叠加额外限速区与终点速度。 */
  static Limits limits(int[] nodeStep, double[] speeds, List<Cap> caps, double exitSpeed) {
    Objects.requireNonNull(speeds, "speeds");
    if (nodeStep.length != speeds.length + 1) {
      throw new IllegalArgumentException("lengths 与 speeds 数量不匹配");
    }
    int samples = nodeStep[speeds.length] + 1;
    double[] limit = new double[samples];
    boolean[] fromCap = new boolean[samples];
    if (speeds.length == 0) {
      limit[0] = Math.max(0.0, exitSpeed);
      fromCap[0] = true;
      return new Limits(limit, fromCap);
    }
    for (int k = 0; k < speeds.length; k++) {
      if (!(speeds[k] > 0.0)) {
        throw new IllegalArgumentException("第 " + k + " 条边的限速必须为正");
      }
      for (int i = nodeStep[k]; i <= nodeStep[k + 1]; i++) {
        limit[i] = i == nodeStep[k] && k > 0 ? Math.min(limit[i], speeds[k]) : speeds[k];
      }
    }
    for (Cap cap : caps == null ? List.<Cap>of() : caps) {
      if (cap == null) {
        continue;
      }
      int from = Math.max(0, (int) Math.ceil(cap.fromBlocks() * STEPS_PER_BLOCK - 1e-9));
      int to = Math.min(samples - 1, (int) Math.floor(cap.toBlocks() * STEPS_PER_BLOCK + 1e-9));
      for (int i = from; i <= to; i++) {
        if (cap.speedBps() < limit[i]) {
          limit[i] = cap.speedBps();
          fromCap[i] = true;
        }
      }
    }
    double exit = Math.max(0.0, exitSpeed);
    if (exit < limit[samples - 1]) {
      limit[samples - 1] = exit;
      fromCap[samples - 1] = true;
    }
    return new Limits(limit, fromCap);
  }

  /**
   * 从终点往回推制动曲线。
   *
   * <p>往回走一步，速度按 {@code v² + 2·b·Δs} 升高，{@code b} 取半步处速度、此处区段限速（巡航速度）与当前末速度下的减速度（中点法）；
   * 撞上此处限速就贴住它，并以它为往回推的新末速度——列车在更低的限速区段里本来就不会更快，更早的制动只需在进入该区段时降到它。
   */
  static SpeedCeiling brake(Limits limits, SpeedCurve curve) {
    double[] cruiseAt = limits.speeds();
    double[] ceiling = cruiseAt.clone();
    boolean[] capBound = limits.fromCap().clone();
    int last = ceiling.length - 1;
    double speed = ceiling[last];
    double endSpeed = speed;
    boolean endFromCap = capBound[last];
    for (int i = last - 1; i >= 0; i--) {
      double cruise = cruiseAt[i];
      // 中点法：低速时一步要走一秒多，减速度在步内变化明显，取半步处的值。
      double half =
          Math.sqrt(speed * speed + curve.decelerationBps2(speed, cruise, endSpeed) * STEP_BLOCKS);
      double decel = curve.decelerationBps2(half, cruise, endSpeed);
      double raised = Math.sqrt(speed * speed + 2.0 * decel * STEP_BLOCKS);
      if (raised >= cruise) {
        speed = cruise;
        endSpeed = cruise;
        endFromCap = capBound[i];
      } else {
        speed = raised;
        capBound[i] = endFromCap;
      }
      ceiling[i] = speed;
    }
    return new SpeedCeiling(ceiling, capBound);
  }

  private static List<Double> boxed(double[] values) {
    List<Double> list = new ArrayList<>(values.length);
    for (double value : values) {
      list.add(value);
    }
    return list;
  }

  /** {@link #cached} 的键：全部输入按值比较。 */
  private record CacheKey(
      List<Double> lengths,
      List<Double> speeds,
      List<Cap> caps,
      double exitSpeed,
      SpeedCurve curve) {}

  private static int steps(double lengthBlocks) {
    return (int) Math.max(1L, Math.round(lengthBlocks * STEPS_PER_BLOCK));
  }
}
