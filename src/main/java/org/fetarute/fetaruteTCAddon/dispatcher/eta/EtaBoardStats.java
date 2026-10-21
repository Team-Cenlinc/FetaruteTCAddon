package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 站牌查询（{@link EtaService#getBoard}）的累计统计：缓存命中、重算次数与重算耗时。
 *
 * <p>用途是定位站牌与站台屏的服务端开销。站牌重算要遍历全网列车快照与待发票据：
 *
 * <ul>
 *   <li>调用线程上的重算（冷启动、旧值太旧、换图或后台不可用）计入 {@code computes}，耗时含拷贝输入。
 *   <li>先给旧值、交给后台刷新的查询计入命中（{@code cacheHits}），同时计入 {@code staleHits}；后台那次重算计入 {@code refreshes}。
 *   <li>主线程上拷贝站牌输入的次数与耗时计入 {@code captures}（调用线程上的重算也包含一次拷贝）。
 * </ul>
 *
 * <p>车站、交路等名称只查内存目录，不读库。
 */
public final class EtaBoardStats {

  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder staleHits = new LongAdder();
  private final LongAdder computes = new LongAdder();
  private final LongAdder totalComputeNanos = new LongAdder();
  private final AtomicLong maxComputeNanos = new AtomicLong();
  private final LongAdder refreshes = new LongAdder();
  private final LongAdder totalRefreshNanos = new LongAdder();
  private final AtomicLong maxRefreshNanos = new AtomicLong();
  private final LongAdder captures = new LongAdder();
  private final LongAdder totalCaptureNanos = new LongAdder();
  private final AtomicLong maxCaptureNanos = new AtomicLong();
  private volatile Instant since;

  /**
   * 创建统计，起算时刻为 {@code since}。
   *
   * @param since 起算时刻
   */
  public EtaBoardStats(Instant since) {
    this.since = Objects.requireNonNull(since, "since");
  }

  /** 记录一次命中缓存、未重算的站牌查询。 */
  public void recordCacheHit() {
    cacheHits.increment();
  }

  /** 记录一次先给旧值、交给后台刷新的站牌查询（也算命中）。 */
  public void recordStaleHit() {
    cacheHits.increment();
    staleHits.increment();
  }

  /**
   * 记录一次调用线程上的重算。
   *
   * @param elapsedNanos 重算耗时（纳秒）
   */
  public void recordCompute(long elapsedNanos) {
    long nanos = Math.max(0L, elapsedNanos);
    computes.increment();
    totalComputeNanos.add(nanos);
    maxComputeNanos.accumulateAndGet(nanos, Math::max);
  }

  /**
   * 记录一次后台刷新。
   *
   * @param elapsedNanos 刷新耗时（纳秒）
   */
  public void recordRefresh(long elapsedNanos) {
    long nanos = Math.max(0L, elapsedNanos);
    refreshes.increment();
    totalRefreshNanos.add(nanos);
    maxRefreshNanos.accumulateAndGet(nanos, Math::max);
  }

  /**
   * 记录一次在主线程上拷贝站牌输入。
   *
   * @param elapsedNanos 拷贝耗时（纳秒）
   */
  public void recordCapture(long elapsedNanos) {
    long nanos = Math.max(0L, elapsedNanos);
    captures.increment();
    totalCaptureNanos.add(nanos);
    maxCaptureNanos.accumulateAndGet(nanos, Math::max);
  }

  /**
   * 清零统计并把起算时刻设为 {@code now}。
   *
   * @param now 新的起算时刻
   */
  public void reset(Instant now) {
    Objects.requireNonNull(now, "now");
    cacheHits.reset();
    staleHits.reset();
    computes.reset();
    totalComputeNanos.reset();
    maxComputeNanos.set(0L);
    refreshes.reset();
    totalRefreshNanos.reset();
    maxRefreshNanos.set(0L);
    captures.reset();
    totalCaptureNanos.reset();
    maxCaptureNanos.set(0L);
    since = now;
  }

  /** 返回当前统计的不可变快照。 */
  public Snapshot snapshot() {
    return new Snapshot(
        since,
        cacheHits.sum(),
        computes.sum(),
        totalComputeNanos.sum(),
        maxComputeNanos.get(),
        staleHits.sum(),
        refreshes.sum(),
        totalRefreshNanos.sum(),
        maxRefreshNanos.get(),
        captures.sum(),
        totalCaptureNanos.sum(),
        maxCaptureNanos.get());
  }

  /**
   * 站牌统计快照。
   *
   * @param since 起算时刻
   * @param cacheHits 没有在调用线程上重算的查询次数（含先给旧值的）
   * @param computes 在调用线程上重算的次数
   * @param totalComputeNanos 调用线程上重算的总耗时（纳秒）
   * @param maxComputeNanos 调用线程上单次重算最长耗时（纳秒）
   * @param staleHits 先给旧值、交给后台刷新的查询次数
   * @param refreshes 后台刷新次数
   * @param totalRefreshNanos 后台刷新总耗时（纳秒）
   * @param maxRefreshNanos 后台单次刷新最长耗时（纳秒）
   * @param captures 主线程上拷贝站牌输入的次数
   * @param totalCaptureNanos 拷贝总耗时（纳秒）
   * @param maxCaptureNanos 单次拷贝最长耗时（纳秒）
   */
  public record Snapshot(
      Instant since,
      long cacheHits,
      long computes,
      long totalComputeNanos,
      long maxComputeNanos,
      long staleHits,
      long refreshes,
      long totalRefreshNanos,
      long maxRefreshNanos,
      long captures,
      long totalCaptureNanos,
      long maxCaptureNanos) {

    /** 保证起算时刻不为 {@code null}。 */
    public Snapshot {
      Objects.requireNonNull(since, "since");
    }

    /** 查询总次数（命中缓存加重算）。 */
    public long calls() {
      return cacheHits + computes;
    }

    /** 缓存命中率，范围 0 至 1；没有查询时为 0。 */
    public double cacheHitRate() {
      long calls = calls();
      return calls == 0L ? 0.0 : (double) cacheHits / calls;
    }

    /** 调用线程上平均每次重算耗时（毫秒）；没有重算时为 0。 */
    public double averageComputeMillis() {
      return computes == 0L ? 0.0 : totalComputeNanos / 1_000_000.0 / computes;
    }

    /** 调用线程上单次重算最长耗时（毫秒）。 */
    public double maxComputeMillis() {
      return maxComputeNanos / 1_000_000.0;
    }

    /** 后台平均每次刷新耗时（毫秒）；没有刷新时为 0。 */
    public double averageRefreshMillis() {
      return refreshes == 0L ? 0.0 : totalRefreshNanos / 1_000_000.0 / refreshes;
    }

    /** 主线程上平均每次拷贝站牌输入的耗时（毫秒）；没有拷贝时为 0。 */
    public double averageCaptureMillis() {
      return captures == 0L ? 0.0 : totalCaptureNanos / 1_000_000.0 / captures;
    }
  }
}
