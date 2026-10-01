package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 站牌查询（{@link EtaService#getBoard}）的累计统计：缓存命中、重算次数、重算耗时，以及重算期间的存储读取次数。
 *
 * <p>用途是定位站牌与站台屏的服务端开销。一次重算会遍历全网列车快照与待发票据，并经由 {@code StorageProvider}
 * 读取车站、运营商、路线与停靠表；这些读取发生在调用线程上（通常是主线程）。
 *
 * <p>存储读取按“重算前后的累计计数差”归属到该次重算。若其他线程在同一时段也做了计数，会被一并计入；目前所有调用方都在主线程，误差可忽略。
 */
public final class EtaBoardStats {

  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder computes = new LongAdder();
  private final LongAdder totalComputeNanos = new LongAdder();
  private final AtomicLong maxComputeNanos = new AtomicLong();
  private final LongAdder totalStorageReads = new LongAdder();
  private final AtomicLong maxStorageReads = new AtomicLong();
  private final AtomicLong storageReadCounter = new AtomicLong();
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

  /**
   * 记录一次重算。
   *
   * @param elapsedNanos 重算耗时（纳秒）
   * @param storageReads 重算期间的存储读取次数
   */
  public void recordCompute(long elapsedNanos, long storageReads) {
    long nanos = Math.max(0L, elapsedNanos);
    long reads = Math.max(0L, storageReads);
    computes.increment();
    totalComputeNanos.add(nanos);
    maxComputeNanos.accumulateAndGet(nanos, Math::max);
    totalStorageReads.add(reads);
    maxStorageReads.accumulateAndGet(reads, Math::max);
  }

  /** 在每一次经由 {@code StorageProvider} 的读取处调用，供重算前后取差。 */
  public void countStorageRead() {
    storageReadCounter.incrementAndGet();
  }

  /** 返回自创建以来的存储读取累计次数（不受 {@link #reset} 影响，只用于取差）。 */
  public long storageReadsSoFar() {
    return storageReadCounter.get();
  }

  /**
   * 清零统计并把起算时刻设为 {@code now}。
   *
   * @param now 新的起算时刻
   */
  public void reset(Instant now) {
    Objects.requireNonNull(now, "now");
    cacheHits.reset();
    computes.reset();
    totalComputeNanos.reset();
    maxComputeNanos.set(0L);
    totalStorageReads.reset();
    maxStorageReads.set(0L);
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
        totalStorageReads.sum(),
        maxStorageReads.get());
  }

  /**
   * 站牌统计快照。
   *
   * @param since 起算时刻
   * @param cacheHits 命中缓存的查询次数
   * @param computes 重算次数
   * @param totalComputeNanos 重算总耗时（纳秒）
   * @param maxComputeNanos 单次重算最长耗时（纳秒）
   * @param totalStorageReads 重算期间的存储读取总次数
   * @param maxStorageReads 单次重算最多的存储读取次数
   */
  public record Snapshot(
      Instant since,
      long cacheHits,
      long computes,
      long totalComputeNanos,
      long maxComputeNanos,
      long totalStorageReads,
      long maxStorageReads) {

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

    /** 平均每次重算耗时（毫秒）；没有重算时为 0。 */
    public double averageComputeMillis() {
      return computes == 0L ? 0.0 : totalComputeNanos / 1_000_000.0 / computes;
    }

    /** 单次重算最长耗时（毫秒）。 */
    public double maxComputeMillis() {
      return maxComputeNanos / 1_000_000.0;
    }

    /** 平均每次重算的存储读取次数；没有重算时为 0。 */
    public double averageStorageReads() {
      return computes == 0L ? 0.0 : (double) totalStorageReads / computes;
    }
  }
}
