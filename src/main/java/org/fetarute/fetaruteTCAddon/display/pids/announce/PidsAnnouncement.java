package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.BroadcastSettings;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 一条站台广播。
 *
 * <p>广播由站台屏快照推出，与屏幕显示同一份数据：快照里正处于某种状态的行，就是此刻“有效”的广播。 是否已对某位玩家播过由 {@link PidsAnnouncementLedger} 按
 * {@link #key()} 判断，所以玩家走进车站时，正在进站的车、已取消的班次会补播一次。
 *
 * @param kind 种类
 * @param key 去重键，见 {@link #active}
 * @param station 车站
 * @param row 触发广播的站台屏行
 * @param previousPlatform 站台变更前的站台（仅 {@link Kind#PLATFORM_CHANGED}）
 */
public record PidsAnnouncement(
    Kind kind, String key, PidsStationKey station, PidsRow row, Optional<String> previousPlatform) {

  public PidsAnnouncement {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(row, "row");
    previousPlatform = previousPlatform == null ? Optional.empty() : previousPlatform;
  }

  /** 不涉及站台变更的广播。 */
  public PidsAnnouncement(Kind kind, String key, PidsStationKey station, PidsRow row) {
    this(kind, key, station, row, Optional.empty());
  }

  /** 播报通道。 */
  public enum Channel {
    /** 短时提示：ActionBar 排队，每条约 3 秒。 */
    ACTION_BAR,
    /** 需要乘客记住的变化：聊天消息。 */
    CHAT
  }

  /** 广播种类。 */
  public enum Kind {
    /** 列车即将进站。 */
    ARRIVING(Channel.ACTION_BAR),
    /** 有列车通过本站。 */
    PASSING(Channel.ACTION_BAR),
    /** 班次取消。 */
    CANCELLED(Channel.CHAT),
    /** 严重晚点。 */
    DELAYED(Channel.CHAT),
    /** 站台变更：已定的站台改了，或没能停到计划站台。 */
    PLATFORM_CHANGED(Channel.CHAT);

    private final Channel channel;

    Kind(Channel channel) {
      this.channel = channel;
    }

    public Channel channel() {
      return channel;
    }
  }

  /**
   * 快照里此刻有效的广播，按预计到达时刻排列。
   *
   * <ul>
   *   <li>进站 / 通过：运行中的列车处于进站状态，或预计在 {@code arriving-lead-seconds} 内到达。回库车与本站终到车也播报进站（文案提醒勿上车）。
   *       站台待定时不播：动态站台在进站前（通常是站咽喉）选台，选好后带站台号播一次，不先播一条没有站台的再补一条。
   *   <li>取消：取消行（快照里有查询窗口内的取消班次，以及计划时刻已过不久的）。
   *   <li>严重晚点：运行中的列车到达本站的晚点达到 {@link Lateness#SEVERELY_LATE_SECONDS}；通过车与回库车不播。
   *   <li>站台变更：这辆车在本站的站台改了（{@link PidsPlatformChanges}），且快照里已是新站台；通过车不播。
   * </ul>
   *
   * <p>只看有列车名的行：票据与预测没有稳定的身份，晚点也要等列车发车后才确定。
   *
   * <p>去重键：
   *
   * <ul>
   *   <li>运行中列车：车站 + 列车名 + 交路 + 停靠序号。列车名跨车次复用（折返换交路沿用原名），往返交路在同一站的停靠序号可能相同，所以要带交路。
   *       进站、通过另带站台号：动态站台选台后站台变了，重播一次即是站台变更提示。
   *   <li>取消班次没有列车名：车站 + 交路代码 + 停靠序号 + 计划到达时刻。交路代码取交路 ID 的最后一段——交路查不到时快照只给交路代码， 查到时给完整
   *       ID，两种写法要得到同一个键。
   * </ul>
   *
   * @param snapshot 车站快照
   * @param now 当前时刻
   * @param settings 播报策略
   */
  public static List<PidsAnnouncement> active(
      PidsSnapshot snapshot, Instant now, BroadcastSettings settings) {
    List<PidsAnnouncement> active = new ArrayList<>();
    PidsStationKey station = snapshot.station();
    Instant lead = now.plusSeconds(settings.arrivingLeadSeconds());
    for (PidsRow row : snapshot.rows()) {
      if (row.status() == PidsRow.Status.CANCELLED) {
        if (settings.triggers().cancelled()) {
          String id =
              routeCode(row.routeId())
                  + "|"
                  + row.stopSequence()
                  + "|"
                  + row.expectedAt().getEpochSecond();
          active.add(
              new PidsAnnouncement(Kind.CANCELLED, key(Kind.CANCELLED, station, id), station, row));
        }
        continue;
      }
      Optional<String> train = row.trainName();
      if (train.isEmpty()) {
        continue;
      }
      String id = train.get() + "|" + row.routeId() + "|" + row.stopSequence();
      boolean approaching =
          row.status() == PidsRow.Status.ARRIVING
              || (row.status() == PidsRow.Status.EN_ROUTE && !row.expectedAt().isAfter(lead));
      Kind approach = row.passing() ? Kind.PASSING : Kind.ARRIVING;
      boolean approachEnabled =
          row.passing() ? settings.triggers().passing() : settings.triggers().arriving();
      if (approaching && approachEnabled && !row.platformPending()) {
        active.add(
            new PidsAnnouncement(
                approach, key(approach, station, id + "|" + row.platform()), station, row));
      }
      boolean severelyLate =
          !row.passing()
              && !row.outOfService()
              && row.delaySeconds().isPresent()
              && Lateness.of(row.delaySeconds().getAsLong()) == Lateness.SEVERELY_LATE;
      if (severelyLate && settings.triggers().delayed()) {
        active.add(
            new PidsAnnouncement(Kind.DELAYED, key(Kind.DELAYED, station, id), station, row));
      }
      if (!row.passing() && settings.triggers().platformChanged()) {
        row.previousPlatform()
            .ifPresent(
                from ->
                    active.add(
                        new PidsAnnouncement(
                            Kind.PLATFORM_CHANGED,
                            key(Kind.PLATFORM_CHANGED, station, id + "|" + row.platform()),
                            station,
                            row,
                            Optional.of(from))));
      }
    }
    return List.copyOf(active);
  }

  private static String key(Kind kind, PidsStationKey station, String id) {
    return kind.name() + "|" + station + "|" + id;
  }

  /** 交路 ID（{@code 运营商:线路:交路}）的最后一段；本身就是交路代码时原样返回。 */
  private static String routeCode(String routeId) {
    return routeId.substring(routeId.lastIndexOf(':') + 1);
  }
}
