package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BossBar 行程进度跟踪器：用“首次观测到 moving 时的 ETA”做时间进度近似。
 *
 * <p>注意：这是近似值，用于 HUD 的直观反馈；并不代表真实轨道距离进度。
 *
 * <p>当列车短暂停车时，进度会冻结并在恢复移动后延续，避免进度条回退造成误解。
 */
public final class BossBarProgressTracker {

  private static final long MIN_TOTAL_MILLIS = 1000L;

  private final Map<String, SegmentState> segments = new ConcurrentHashMap<>();

  public float progress(
      String trainName, int routeIndex, long etaEpochMillis, long nowEpochMillis, boolean moving) {
    if (trainName == null || trainName.isBlank()) {
      return 0.0f;
    }

    SegmentState state =
        segments.compute(
            trainName,
            (key, existing) -> {
              SegmentState next = existing == null ? SegmentState.initial(routeIndex) : existing;
              if (next.routeIndex != routeIndex) {
                next = SegmentState.initial(routeIndex);
              }
              if (moving) {
                next = next.resumeOrAdvance(nowEpochMillis, etaEpochMillis);
              } else {
                next = next.pause(nowEpochMillis);
              }
              return next;
            });

    return state.lastProgress;
  }

  public void clear() {
    segments.clear();
  }

  public void retain(java.util.Set<String> activeTrainNames) {
    if (activeTrainNames == null || activeTrainNames.isEmpty()) {
      segments.clear();
      return;
    }
    segments.keySet().retainAll(activeTrainNames);
  }

  private static final class SegmentState {
    private final int routeIndex;
    private final boolean moving;
    private final long startMillis;
    private final long totalMillis;
    private final float lastProgress;

    private SegmentState(
        int routeIndex, boolean moving, long startMillis, long totalMillis, float lastProgress) {
      this.routeIndex = routeIndex;
      this.moving = moving;
      this.startMillis = startMillis;
      this.totalMillis = totalMillis;
      this.lastProgress = lastProgress;
    }

    private static SegmentState initial(int routeIndex) {
      return new SegmentState(routeIndex, false, 0L, 0L, 0.0f);
    }

    private SegmentState resumeOrAdvance(long nowMillis, long etaMillis) {
      if (nowMillis <= 0L) {
        return this;
      }
      if (!moving) {
        return resumeFromPause(nowMillis, etaMillis);
      }
      if (totalMillis <= 0L) {
        if (etaMillis <= 0L) {
          return this;
        }
        long total = Math.max(MIN_TOTAL_MILLIS, etaMillis - nowMillis);
        return new SegmentState(routeIndex, true, nowMillis, total, 0.0f);
      }
      float progress = clampProgress((float) (nowMillis - startMillis) / (float) totalMillis);
      return new SegmentState(routeIndex, true, startMillis, totalMillis, progress);
    }

    private SegmentState pause(long nowMillis) {
      if (!moving || nowMillis <= 0L || totalMillis <= 0L) {
        return new SegmentState(routeIndex, false, startMillis, totalMillis, lastProgress);
      }
      float progress = clampProgress((float) (nowMillis - startMillis) / (float) totalMillis);
      return new SegmentState(routeIndex, false, startMillis, totalMillis, progress);
    }

    private SegmentState resumeFromPause(long nowMillis, long etaMillis) {
      float progress = clampProgress(lastProgress);
      long total = totalMillis;
      if (etaMillis > nowMillis) {
        if (progress > 0.0f && progress < 1.0f) {
          long remaining = etaMillis - nowMillis;
          long computed = Math.round(remaining / (1.0f - progress));
          total = Math.max(MIN_TOTAL_MILLIS, computed);
        } else {
          total = Math.max(MIN_TOTAL_MILLIS, etaMillis - nowMillis);
          progress = 0.0f;
        }
      } else if (total <= 0L) {
        total = MIN_TOTAL_MILLIS;
      }
      long start = nowMillis - Math.round(progress * total);
      return new SegmentState(routeIndex, true, start, total, progress);
    }

    private static float clampProgress(float value) {
      if (value < 0.0f) {
        return 0.0f;
      }
      if (value > 1.0f) {
        return 1.0f;
      }
      return value;
    }
  }
}
