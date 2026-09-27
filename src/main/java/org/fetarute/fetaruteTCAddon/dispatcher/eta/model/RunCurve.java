package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.List;
import java.util.Objects;

/**
 * 一段走行的运行曲线：列车在限速包络下以恒定加速度起步、以恒定减速度制动，逐节点给出到达时刻。
 *
 * <p>包络取三样的最小值：每条边的限速、额外限速区（进站限速区）、终点速度上限。先正向按加速度推一遍、再反向按减速度推一遍，
 * 两遍取小就是"能多快就多快，但在任何一处限速之前都来得及刹住"的速度曲线。
 *
 * <p>这是理想司机：它在限速区起点正好降到限速。运行时的控车只在触发点前一段距离内才开始下压速度，实际常常晚刹、在限速区里继续减速，
 * 所以本曲线在长站距、高限速的区段上略偏慢——偏差方向是安全的：早到的车在站里被计划扣留吸收，晚到的车会把晚点传给后面每一班。
 *
 * <p>沿里程按 {@link #STEPS_PER_BLOCK} 等分离散。节点都落在整数格上，因此逐节点时刻是精确的累加结果，不需要插值； 相邻两点之间按匀加减速取平均速度（{@code
 * 2·Δs / (v₁ + v₂)}），在纯加速或纯减速的步长内是精确解。
 */
final class RunCurve {

  /** 每格的离散步数：0.25 格一步，时分误差远小于 0.1 秒。 */
  static final int STEPS_PER_BLOCK = 4;

  private static final double STEP_BLOCKS = 1.0 / STEPS_PER_BLOCK;

  private RunCurve() {}

  /**
   * 额外限速区：里程 {@code [fromBlocks, toBlocks]} 内速度不超过 {@code speedBps}。
   *
   * @param fromBlocks 起点里程（格，相对本段起点）
   * @param toBlocks 终点里程（格，相对本段起点）
   * @param speedBps 限速（格/秒），必须为正
   */
  record Cap(double fromBlocks, double toBlocks, double speedBps) {
    Cap {
      if (!Double.isFinite(speedBps) || speedBps <= 0.0) {
        throw new IllegalArgumentException("speedBps 必须为正数");
      }
    }
  }

  /**
   * 计算到达每个节点的秒数。
   *
   * @param lengths 各边长度（格），均为正；按 {@link #quantize} 落到网格上（整数格不受影响，列车正处在边中间时首边是剩余长度）
   * @param speeds 各边限速（格/秒），均为正
   * @param caps 额外限速区，可为空
   * @param entrySpeed 起点速度（停车点起步为 0），超过首边限速时按首边限速
   * @param exitSpeed 终点速度上限（进站限速或 0）；不设上限传 {@link Double#POSITIVE_INFINITY}
   * @param accel 加速度（格/秒²），必须为正
   * @param decel 减速度（格/秒²），必须为正
   * @return 长度为 {@code 边数 + 1} 的到达秒数，首项为 0
   */
  static double[] nodeTimes(
      double[] lengths,
      double[] speeds,
      List<Cap> caps,
      double entrySpeed,
      double exitSpeed,
      double accel,
      double decel) {
    Objects.requireNonNull(lengths, "lengths");
    Objects.requireNonNull(speeds, "speeds");
    if (lengths.length != speeds.length) {
      throw new IllegalArgumentException("lengths 与 speeds 数量不匹配");
    }
    if (!(accel > 0.0) || !(decel > 0.0)) {
      throw new IllegalArgumentException("加减速必须为正数");
    }
    int edges = lengths.length;
    int[] nodeStep = new int[edges + 1];
    for (int k = 0; k < edges; k++) {
      if (!(lengths[k] > 0.0) || !Double.isFinite(lengths[k]) || !(speeds[k] > 0.0)) {
        throw new IllegalArgumentException("第 " + k + " 条边的长度与限速必须为正");
      }
      nodeStep[k + 1] = nodeStep[k] + steps(lengths[k]);
    }
    double[] times = new double[edges + 1];
    int samples = nodeStep[edges] + 1;
    if (samples == 1) {
      return times;
    }

    double[] limit = speedLimits(nodeStep, speeds, caps == null ? List.of() : caps, samples);
    limit[samples - 1] = Math.min(limit[samples - 1], Math.max(0.0, exitSpeed));

    double[] speed = new double[samples];
    speed[0] = Math.min(limit[0], Math.max(0.0, entrySpeed));
    double accelStep = 2.0 * accel * STEP_BLOCKS;
    for (int i = 1; i < samples; i++) {
      speed[i] = Math.min(limit[i], Math.sqrt(speed[i - 1] * speed[i - 1] + accelStep));
    }
    double decelStep = 2.0 * decel * STEP_BLOCKS;
    for (int i = samples - 2; i >= 0; i--) {
      speed[i] = Math.min(speed[i], Math.sqrt(speed[i + 1] * speed[i + 1] + decelStep));
    }

    double elapsed = 0.0;
    int node = 1;
    // 正反两遍之后只有首末两点可能为 0；只有整段只有一步、首末都要求静止时两点之和才会是 0，按一步能达到的速度兜底。
    double floor = Math.sqrt(2.0 * Math.min(accel, decel) * STEP_BLOCKS);
    for (int i = 1; i < samples; i++) {
      elapsed += 2.0 * STEP_BLOCKS / Math.max(speed[i - 1] + speed[i], floor);
      if (i == nodeStep[node]) {
        times[node++] = elapsed;
      }
    }
    return times;
  }

  /**
   * 把长度落到离散网格上：四舍五入到 {@code 1 / STEPS_PER_BLOCK} 格，至少一步。
   *
   * <p>调用方计算节点里程（例如进站限速区的起点）时必须用同一个值，否则限速区会与节点错开一两步。
   */
  static double quantize(double lengthBlocks) {
    return steps(lengthBlocks) * STEP_BLOCKS;
  }

  private static int steps(double lengthBlocks) {
    return (int) Math.max(1L, Math.round(lengthBlocks * STEPS_PER_BLOCK));
  }

  /** 逐点限速：边内取本边限速，节点处取两侧较小者（进下一条边之前必须已经降到它的限速），再叠加额外限速区。 */
  private static double[] speedLimits(
      int[] nodeStep, double[] speeds, List<Cap> caps, int samples) {
    double[] limit = new double[samples];
    for (int k = 0; k < speeds.length; k++) {
      for (int i = nodeStep[k]; i <= nodeStep[k + 1]; i++) {
        limit[i] = i == nodeStep[k] && k > 0 ? Math.min(limit[i], speeds[k]) : speeds[k];
      }
    }
    for (Cap cap : caps) {
      if (cap == null) {
        continue;
      }
      int from = Math.max(0, (int) Math.ceil(cap.fromBlocks() * STEPS_PER_BLOCK - 1e-9));
      int to = Math.min(samples - 1, (int) Math.floor(cap.toBlocks() * STEPS_PER_BLOCK + 1e-9));
      for (int i = from; i <= to; i++) {
        limit[i] = Math.min(limit[i], cap.speedBps());
      }
    }
    return limit;
  }
}
