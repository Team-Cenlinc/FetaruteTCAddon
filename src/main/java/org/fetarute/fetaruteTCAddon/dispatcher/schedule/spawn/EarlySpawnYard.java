package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 提前出车占用车库股道前的检查：只做判定，现场数据由调用方收集。
 *
 * <p>提前出的车在车库股道上一直停到计划发车时刻。这段时间里别的车要从这个车库出库、回库，或者正在驶向车库， 每一件事都需要它可用的股道里至少还剩一条空着；本车占掉某条股道后，
 * 只要有一件事再无股道可用，这条股道就不能选。别的车只是短暂经过股道（出库即走、回库即销毁），所以剩一条空股道就够，不按件数累计。
 */
public final class EarlySpawnYard {

  private EarlySpawnYard() {}

  /** 别的车对车库的一次使用。 */
  public enum UseKind {
    /** 别的班次从车库出库。 */
    DEPARTURE,
    /** 按时刻表回库。 */
    ARRIVAL,
    /** 列车正驶向车库回库。 */
    INBOUND
  }

  /**
   * 别的车在本车计划发车之前对车库的一次使用。
   *
   * @param kind 出库、回库或正在回库
   * @param subject 车次、交路或列车名，用于说明
   * @param at 计划时刻；正在回库的车没有
   * @param tracks 这次使用可以落在哪些股道上
   */
  public record Use(UseKind kind, String subject, Optional<Instant> at, Set<String> tracks) {
    public Use {
      Objects.requireNonNull(kind, "kind");
      subject = subject == null ? "" : subject;
      at = at == null ? Optional.empty() : at;
      tracks = tracks == null ? Set.of() : Set.copyOf(tracks);
    }
  }

  /** 选不出股道的原因。 */
  public enum Reason {
    /** 查不到这个车库有哪些股道。 */
    YARD_UNKNOWN,
    /** 指定的股道上现在有车。 */
    TRACK_OCCUPIED,
    /** 车库里没有空股道。 */
    NO_FREE_TRACK,
    /** 占掉这条股道后，别的车无股道可用。 */
    NEEDED_BY_OTHERS
  }

  /**
   * 选不出股道时的说明。
   *
   * @param reason 原因
   * @param track 涉及的股道；车库股道未知时为空串
   * @param use 会被挡住的那次使用（仅 {@link Reason#NEEDED_BY_OTHERS}）
   */
  public record Blocker(Reason reason, String track, Optional<Use> use) {
    public Blocker {
      Objects.requireNonNull(reason, "reason");
      track = track == null ? "" : track;
      use = use == null ? Optional.empty() : use;
    }
  }

  /**
   * 判定结果：选定的股道，或选不出的原因，二者恰有其一。
   *
   * @param track 选定的股道
   * @param blocker 选不出的原因
   */
  public record Decision(Optional<String> track, Optional<Blocker> blocker) {
    public Decision {
      track = track == null ? Optional.empty() : track;
      blocker = blocker == null ? Optional.empty() : blocker;
      if (track.isPresent() == blocker.isPresent()) {
        throw new IllegalArgumentException("股道与原因必须恰有其一");
      }
    }

    public static Decision use(String track) {
      return new Decision(Optional.of(track), Optional.empty());
    }

    public static Decision blocked(Blocker blocker) {
      return new Decision(Optional.empty(), Optional.of(blocker));
    }

    public static Decision blocked(Reason reason, String track) {
      return blocked(new Blocker(reason, track, Optional.empty()));
    }
  }

  /**
   * 从候选股道里挑一条给提前出的车。
   *
   * <p>能选的股道要空着，且占掉它之后每次使用都还剩一条空股道；能选的有多条时，挑被最少次使用列为可用的那条， 把别的车更可能要用的股道留出来。
   *
   * @param candidates 本车可以用的股道，按优先顺序；固定写法的出库点只有一条
   * @param free 现在空着的股道
   * @param uses 本车计划发车之前，别的车对车库的使用
   */
  public static Decision choose(List<String> candidates, Set<String> free, List<Use> uses) {
    List<String> ordered = candidates == null ? List.of() : dedupe(candidates);
    if (ordered.isEmpty()) {
      return Decision.blocked(Reason.YARD_UNKNOWN, "");
    }
    Set<String> freeKeys = keys(free);
    List<Use> relevant = uses == null ? List.of() : uses;
    String best = null;
    int bestDemand = Integer.MAX_VALUE;
    Blocker firstBlocker = null;
    for (String track : ordered) {
      String key = key(track);
      if (!freeKeys.contains(key)) {
        continue;
      }
      Optional<Use> starved = starvedBy(key, freeKeys, relevant);
      if (starved.isPresent()) {
        if (firstBlocker == null) {
          firstBlocker = new Blocker(Reason.NEEDED_BY_OTHERS, track, starved);
        }
        continue;
      }
      int demand = demandOn(key, relevant);
      if (demand < bestDemand) {
        best = track;
        bestDemand = demand;
      }
    }
    if (best != null) {
      return Decision.use(best);
    }
    if (firstBlocker != null) {
      return Decision.blocked(firstBlocker);
    }
    if (ordered.size() == 1) {
      return Decision.blocked(Reason.TRACK_OCCUPIED, ordered.get(0));
    }
    return Decision.blocked(Reason.NO_FREE_TRACK, "");
  }

  /** 占掉这条股道后，第一次再无空股道可用的使用。 */
  private static Optional<Use> starvedBy(String taken, Set<String> free, List<Use> uses) {
    for (Use use : uses) {
      Set<String> tracks = keys(use.tracks());
      if (!tracks.contains(taken)) {
        continue;
      }
      boolean anotherFree = false;
      for (String track : tracks) {
        if (!track.equals(taken) && free.contains(track)) {
          anotherFree = true;
          break;
        }
      }
      if (!anotherFree) {
        return Optional.of(use);
      }
    }
    return Optional.empty();
  }

  private static int demandOn(String track, List<Use> uses) {
    int count = 0;
    for (Use use : uses) {
      if (keys(use.tracks()).contains(track)) {
        count++;
      }
    }
    return count;
  }

  private static List<String> dedupe(List<String> tracks) {
    Set<String> seen = new HashSet<>();
    List<String> out = new ArrayList<>();
    for (String track : tracks) {
      if (track != null && !track.isBlank() && seen.add(key(track))) {
        out.add(track.trim());
      }
    }
    return out;
  }

  private static Set<String> keys(Set<String> tracks) {
    Set<String> out = new LinkedHashSet<>();
    if (tracks != null) {
      for (String track : tracks) {
        if (track != null && !track.isBlank()) {
          out.add(key(track));
        }
      }
    }
    return out;
  }

  private static String key(String track) {
    return track.trim().toUpperCase(Locale.ROOT);
  }
}
