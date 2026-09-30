package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SpeedEnvelope;

/**
 * 逐 tick 限速斜坡：在两次调度周期之间，让 TrainCarts 限速沿本周期的速度包络连续下降。
 *
 * <p>TrainCarts 的 {@code speedLimit} 是硬上限：每个物理步都把实体最大速度设成它，改低后下一 tick 就直接截速，不经过任何减速过程； {@code
 * WaitAcceleration} 只作用于跟车、互斥区与阻挡牌，与 speedLimit 无关。调度周期（实服每秒一次）只在周期点算目标速度， 减速于是成了
 * “每秒切一刀、中间匀速”。本类在周期之间按列车实际走过的距离重算包络，每 tick 只下调一小步。
 *
 * <p>三条规则保证它只让减速更平顺，不放宽任何限制：
 *
 * <ul>
 *   <li>只降不升：写入值永不高于上一次写入值，因而也不高于本周期命令值；
 *   <li>让位：发现限速被别处改写（STOP、居中、重发等）立即退出，不与其它控车路径争写；
 *   <li>有寿命：连续若干调度周期没被刷新就退出，退回逐周期保持。
 * </ul>
 *
 * <p>加速中写限速不会打断提速：本插件的发车动作（{@link CurveLaunchAction}）每 tick 直接读当前限速，不像 TrainCarts 的 launch
 * 那样遇限速变化就重新规划。
 *
 * <p>登记时给了加速度的，斜坡还负责保速：发车动作到速即结束，之后列车若低于当前限速（被推挤、别的插件改了速度等）且身上没有别的 TrainCarts 动作，当 tick
 * 就补牵引到限速，不等下一个调度周期。牵引目标始终就是当前限速。
 *
 * <p>斜坡按实际走过的里程推算车头位置（{@link #headProgressBlocks}），供下一周期的调度层取代按直线距离插值的估计。
 *
 * <p>包络中的保持约束（{@link
 * SpeedEnvelope#withHold}，即到下一停车点的速度天花板：进站限速区、沿途慢速边与到站速度）另供没有速度上下文的控车调用（过节点时的推进放行）封顶。
 * 只认保持约束、不认周期命令值：周期命令值里有上调限幅的滞后与已驶过区段的限速，拿它封顶会扣住推进放行的补牵引。 保持约束是按制动曲线推出的物理上界，远处就是线路速度，按它封顶不会压低出站加速。
 *
 * <p>仅在服务器主线程使用。
 */
public final class SpeedLimitRamp {

  /** 驱动逐 tick 推进的外部时钟。 */
  @FunctionalInterface
  public interface TickDriver {
    /**
     * 开始每 tick 调用一次 {@code tick}。
     *
     * @return 停止句柄；无法开始时返回 {@code null}，此时斜坡保持惰性
     */
    Runnable start(Runnable tick);
  }

  private static final double TICKS_PER_SECOND = 20.0;

  /** 单次下调的最小步长（blocks/tick）。0.005 bpt = 0.1 bps：截速察觉不到，也不必每 tick 改写一次属性。 */
  static final double WRITE_STEP_BPT = 0.005;

  /** 判定“限速被别处改写”的容差（blocks/tick）。 */
  private static final double FOREIGN_WRITE_TOLERANCE_BPT = 1.0e-9;

  private static final Logger LOGGER = Logger.getLogger(SpeedLimitRamp.class.getName());

  private final TickDriver driver;
  private final Map<Object, Entry> entries = new IdentityHashMap<>();
  private Runnable stopHandle;

  /** 使用 Bukkit 调度器驱动。 */
  public SpeedLimitRamp() {
    this(bukkitDriver());
  }

  /**
   * @param driver 逐 tick 时钟；返回 {@code null} 时斜坡不生效
   */
  public SpeedLimitRamp(TickDriver driver) {
    this.driver = driver;
  }

  /**
   * 记录一次带速度上下文的调度命令，并按包络准备逐 tick 下调。
   *
   * <p>调用方须刚把 {@code commandedBps} 写入 {@code properties}；写入值以属性当前值为准（TrainCarts 会夹到合法范围）。
   *
   * @param train 运行中的列车
   * @param properties 该车属性
   * @param commandedBps 本周期写入的限速（blocks/s）
   * @param envelope 从车头量起的随距离约束；为空时不登记
   * @param ttlTicks 未被刷新时的最长存活 tick 数
   */
  public void arm(
      RuntimeTrainHandle train,
      TrainProperties properties,
      double commandedBps,
      SpeedEnvelope envelope,
      int ttlTicks) {
    arm(train, properties, commandedBps, envelope, ttlTicks, 0.0);
  }

  /**
   * 同 {@link #arm(RuntimeTrainHandle, TrainProperties, double, SpeedEnvelope, int)}，另按 {@code
   * accelBpt2} 在周期之间保速：列车低于当前限速时补牵引到限速。
   *
   * @param accelBpt2 补牵引用的加速度（blocks/tick²）；非正时不补牵引
   */
  public void arm(
      RuntimeTrainHandle train,
      TrainProperties properties,
      double commandedBps,
      SpeedEnvelope envelope,
      int ttlTicks,
      double accelBpt2) {
    Object key = keyOf(train);
    if (key == null
        || properties == null
        || envelope == null
        || envelope.isEmpty()
        || !Double.isFinite(commandedBps)
        || ttlTicks <= 0
        || !ensureTicking()) {
      release(train);
      return;
    }
    double written = properties.getSpeedLimit();
    if (!Double.isFinite(written)) {
      release(train);
      return;
    }
    entries.put(
        key,
        new Entry(
            train,
            properties,
            envelope,
            Math.max(0.0, commandedBps),
            written,
            ttlTicks,
            Double.isFinite(accelBpt2) && accelBpt2 > 0.0 ? accelBpt2 : 0.0));
  }

  /** 撤销该车的斜坡与命令记录（STOP、硬停、重发、静止时调用）。 */
  public void release(RuntimeTrainHandle train) {
    Object key = keyOf(train);
    if (key == null) {
      return;
    }
    entries.remove(key);
    stopIfIdle();
  }

  /**
   * 该车保持约束此刻给出的上限（blocks/s），供没有速度上下文的控车调用封顶。
   *
   * <p>取保持约束在当前推算位置的值，不与斜坡已写入的限速取小：已写入值里可能含边限速、上调限幅的滞后，拿它封顶会扣住推进放行的补牵引。
   * 没有登记、列车已无效或已停、限速已被别处改写，或保持约束此处不设限时返回空。
   */
  public OptionalDouble holdLimitBps(RuntimeTrainHandle train) {
    Object key = keyOf(train);
    if (key == null) {
      return OptionalDouble.empty();
    }
    Entry entry = entries.get(key);
    if (entry == null) {
      return OptionalDouble.empty();
    }
    if (!entry.stillOwnsSpeedLimit()) {
      entries.remove(key);
      stopIfIdle();
      return OptionalDouble.empty();
    }
    double hold = entry.envelope.holdLimitBps(entry.traveledBlocks);
    return Double.isFinite(hold) ? OptionalDouble.of(hold) : OptionalDouble.empty();
  }

  /**
   * 按实际走过的里程推算的车头位置：登记时车头在 {@code nodeKey} 之后多远，再加上此后走过的距离。
   *
   * <p>包络没有记下取样位置、取样节点不是 {@code nodeKey}（车头已过下一个图节点）、没有登记或限速已被别处改写时返回空， 调用方退回自己的估计。
   *
   * @param nodeKey 车头之前最近经过的图节点
   */
  public OptionalDouble headProgressBlocks(RuntimeTrainHandle train, String nodeKey) {
    Object key = keyOf(train);
    if (key == null || nodeKey == null) {
      return OptionalDouble.empty();
    }
    Entry entry = entries.get(key);
    if (entry == null
        || !entry.stillOwnsSpeedLimit()
        || !entry.envelope.originKey().map(nodeKey::equals).orElse(false)) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(entry.envelope.originBlocks() + entry.traveledBlocks);
  }

  /**
   * 控车路径在没有新包络的情况下改写了限速后调用：写入值不高于斜坡已写入值时认下，避免被当作外部改写而退出；更高则撤销登记—— 斜坡只降不升，留着它会在下一 tick 把刚抬高的限速压回去。
   */
  public void acknowledgeWrite(RuntimeTrainHandle train, TrainProperties properties) {
    Object key = keyOf(train);
    if (key == null || properties == null) {
      return;
    }
    Entry entry = entries.get(key);
    if (entry == null || entry.properties != properties) {
      return;
    }
    double written = properties.getSpeedLimit();
    if (!Double.isFinite(written) || written > entry.lastWrittenBpt + FOREIGN_WRITE_TOLERANCE_BPT) {
      entries.remove(key);
      stopIfIdle();
      return;
    }
    entry.lastWrittenBpt = written;
  }

  /**
   * 推进一 tick：累计行驶距离，按包络下调限速。
   *
   * <p>改写 TrainCarts 属性会同步触发 {@code onPropertiesChanged}，可能经由事件回到控车路径改动登记表，所以遍历快照。
   * 单车出错时撤销该车登记（退回逐周期保持），不影响其它列车，也不让同一个异常每 tick 重复抛出。
   */
  public void tick() {
    if (!entries.isEmpty()) {
      List<Map.Entry<Object, Entry>> snapshot = new ArrayList<>(entries.entrySet());
      for (Map.Entry<Object, Entry> slot : snapshot) {
        Entry entry = slot.getValue();
        if (entries.get(slot.getKey()) != entry) {
          continue;
        }
        boolean keep;
        try {
          keep = entry.step();
        } catch (RuntimeException ex) {
          LOGGER.log(Level.WARNING, "逐 tick 限速斜坡出错，撤销该车登记", ex);
          keep = false;
        }
        if (!keep) {
          entries.remove(slot.getKey(), entry);
        }
      }
    }
    stopIfIdle();
  }

  /** 当前登记的列车数（测试与诊断用）。 */
  int size() {
    return entries.size();
  }

  private boolean ensureTicking() {
    if (stopHandle != null) {
      return true;
    }
    if (driver == null) {
      return false;
    }
    stopHandle = driver.start(this::tick);
    return stopHandle != null;
  }

  private void stopIfIdle() {
    if (!entries.isEmpty() || stopHandle == null) {
      return;
    }
    Runnable handle = stopHandle;
    stopHandle = null;
    handle.run();
  }

  private static Object keyOf(RuntimeTrainHandle train) {
    return train == null ? null : train.physicalRuntimeIdentity();
  }

  /**
   * Bukkit 调度器时钟。
   *
   * <p>单测环境没有插件类加载器，{@link JavaPlugin#getProvidingPlugin} 会抛异常，此时返回 {@code null}，斜坡保持惰性。
   */
  static TickDriver bukkitDriver() {
    return tick -> {
      try {
        Plugin plugin = JavaPlugin.getProvidingPlugin(SpeedLimitRamp.class);
        if (!plugin.isEnabled()) {
          return null;
        }
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, tick, 1L, 1L);
        return task::cancel;
      } catch (RuntimeException | LinkageError ex) {
        return null;
      }
    };
  }

  /** 单车斜坡状态。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "只保存 TrainCarts 列车句柄与属性对象的引用用于逐 tick 控车，不复制也不对外暴露")
  private static final class Entry {
    private final RuntimeTrainHandle train;
    private final TrainProperties properties;
    private final SpeedEnvelope envelope;
    private final double commandedBps;
    private final double accelBpt2;
    private double lastWrittenBpt;
    private double traveledBlocks;
    private int remainingTicks;

    private Entry(
        RuntimeTrainHandle train,
        TrainProperties properties,
        SpeedEnvelope envelope,
        double commandedBps,
        double lastWrittenBpt,
        int remainingTicks,
        double accelBpt2) {
      this.train = train;
      this.properties = properties;
      this.envelope = envelope;
      this.commandedBps = commandedBps;
      this.lastWrittenBpt = lastWrittenBpt;
      this.remainingTicks = remainingTicks;
      this.accelBpt2 = accelBpt2;
    }

    /** 列车仍在运行，且限速仍是本斜坡最后写入的值。 */
    private boolean stillOwnsSpeedLimit() {
      if (!train.isValid() || !train.isMoving()) {
        return false;
      }
      double current = properties.getSpeedLimit();
      return Double.isFinite(current)
          && Math.abs(current - lastWrittenBpt) <= FOREIGN_WRITE_TOLERANCE_BPT;
    }

    /**
     * @return 是否继续保留
     */
    private boolean step() {
      if (--remainingTicks < 0 || !stillOwnsSpeedLimit()) {
        return false;
      }
      // 实体速度向量在被限速截住后并不缩短（TrainCarts 只截本步位移），直接读会高估；实际位移不超过当前限速。
      double speedBpt = train.currentSpeedBlocksPerTick();
      double movedBpt =
          Double.isFinite(speedBpt) && speedBpt > 0.0 ? Math.min(speedBpt, lastWrittenBpt) : 0.0;
      traveledBlocks += movedBpt;
      double limitBps = Math.min(commandedBps, envelope.limitBps(traveledBlocks));
      if (Double.isFinite(limitBps)) {
        double limitBpt = Math.max(0.0, limitBps / TICKS_PER_SECOND);
        if (limitBpt <= lastWrittenBpt - WRITE_STEP_BPT) {
          properties.setSpeedLimit(limitBpt);
          lastWrittenBpt = properties.getSpeedLimit();
        }
      }
      holdSpeed(speedBpt);
      return true;
    }

    /**
     * 保速：低于当前限速就补牵引到限速。正在制动（速度向量高于限速）时不补；已有本插件的发车动作时 {@link RuntimeTrainHandle#accelerateTo}
     * 自己不重发；身上有别的 TrainCarts 动作（停站、居中）时不补，免得排到它后面。
     */
    private void holdSpeed(double speedBpt) {
      if (accelBpt2 <= 0.0
          || !Double.isFinite(speedBpt)
          || speedBpt >= lastWrittenBpt - TrainLaunchManager.TRACTION_EPSILON_BPT
          || train.hasForeignAction()) {
        return;
      }
      train.accelerateTo(lastWrittenBpt, accelBpt2);
    }
  }
}
