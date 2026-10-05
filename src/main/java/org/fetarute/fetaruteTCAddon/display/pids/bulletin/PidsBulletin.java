package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 站台屏公告：运营方手写的一条通知，在本运营商车站的站台屏上轮播（只上屏，不发聊天）。
 *
 * <p>公告属于一个运营商；车站、线路为空表示不限定。屏幕绑定的车站属于这个运营商、站码在车站清单里（或不限车站）、 屏幕显示的线路与线路清单有交集（或不限线路）时显示。 时段为半开区间
 * {@code [startsAt, endsAt)}，任一端为空表示不限。
 *
 * @param id 编号
 * @param companyId 发布公告的公司
 * @param operatorCode 运营商代码（大写）
 * @param stations 只在这些车站显示（站码，大写）；为空表示该运营商全部车站
 * @param lines 只在显示这些线路的屏幕上显示（线路代码，大写）；为空表示不限
 * @param level 等级
 * @param title 标题，中文必填、英文可为空
 * @param body 正文，中英文都可为空
 * @param startsAt 开始时刻；为空表示立即
 * @param endsAt 结束时刻；为空表示长期
 * @param createdBy 发布者；控制台发布时为空
 * @param createdAt 发布时刻
 * @param updatedAt 最后修改时刻
 */
public record PidsBulletin(
    UUID id,
    UUID companyId,
    String operatorCode,
    Set<String> stations,
    Set<String> lines,
    Level level,
    Text title,
    Text body,
    Optional<Instant> startsAt,
    Optional<Instant> endsAt,
    Optional<UUID> createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public PidsBulletin {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(companyId, "companyId");
    operatorCode = upper(Objects.requireNonNull(operatorCode, "operatorCode"));
    if (operatorCode.isEmpty()) {
      throw new IllegalArgumentException("operatorCode 不能为空");
    }
    stations = codes(stations);
    lines = codes(lines);
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(title, "title");
    if (title.primary().isEmpty()) {
      throw new IllegalArgumentException("中文标题不能为空");
    }
    Objects.requireNonNull(body, "body");
    startsAt = startsAt == null ? Optional.empty() : startsAt;
    endsAt = endsAt == null ? Optional.empty() : endsAt;
    createdBy = createdBy == null ? Optional.empty() : createdBy;
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** 等级。 */
  public enum Level {
    /** 一般：钢蓝图标块，与宣传页隔段交替。 */
    NORMAL,
    /** 重要：琥珀图标块，每段副页都放，宣传页暂停。 */
    IMPORTANT
  }

  /** 公告此刻的状态。 */
  public enum Status {
    /** 还没到开始时刻。 */
    SCHEDULED,
    /** 生效中。 */
    ACTIVE,
    /** 已过结束时刻。 */
    ENDED
  }

  /**
   * 一段中英文。
   *
   * @param primary 中文（去掉首尾空白）
   * @param secondary 英文（去掉首尾空白）；可为空串
   */
  public record Text(String primary, String secondary) {

    public Text {
      primary = primary == null ? "" : primary.strip();
      secondary = secondary == null ? "" : secondary.strip();
    }

    /** 中英文都没写。 */
    public boolean isEmpty() {
      return primary.isEmpty() && secondary.isEmpty();
    }
  }

  /** 只在这些车站显示（大写站码，按字典序）；不可修改。 */
  @Override
  public Set<String> stations() {
    return Collections.unmodifiableSet(stations);
  }

  /** 只在显示这些线路的屏幕上显示（大写线路代码，按字典序）；不可修改。 */
  @Override
  public Set<String> lines() {
    return Collections.unmodifiableSet(lines);
  }

  public boolean important() {
    return level == Level.IMPORTANT;
  }

  /** 此刻的状态。 */
  public Status status(Instant now) {
    if (startsAt.isPresent() && now.isBefore(startsAt.get())) {
      return Status.SCHEDULED;
    }
    if (endsAt.isPresent() && !now.isBefore(endsAt.get())) {
      return Status.ENDED;
    }
    return Status.ACTIVE;
  }

  /**
   * 是否显示在某块屏幕上。
   *
   * @param station 屏幕绑定的车站
   * @param screenLines 屏幕显示的线路代码（屏幕设了线路过滤时为过滤清单，否则为停靠所选站台的线路）
   */
  public boolean appliesTo(PidsStationKey station, Collection<String> screenLines) {
    if (!operatorCode.equals(station.operatorCode())) {
      return false;
    }
    if (!stations.isEmpty() && !stations.contains(station.stationCode())) {
      return false;
    }
    return lines.isEmpty()
        || screenLines.stream().map(PidsBulletin::upper).anyMatch(lines::contains);
  }

  /** 内容版本：编号加最后修改时刻。改过的公告视为新公告，轮播与排版缓存都按新的算。 */
  public String revision() {
    return id + "@" + updatedAt.toEpochMilli();
  }

  /** 改过内容的副本。 */
  public PidsBulletin edited(
      Set<String> stations,
      Set<String> lines,
      Level level,
      Text title,
      Text body,
      Optional<Instant> startsAt,
      Optional<Instant> endsAt,
      Instant now) {
    return new PidsBulletin(
        id,
        companyId,
        operatorCode,
        stations,
        lines,
        level,
        title,
        body,
        startsAt,
        endsAt,
        createdBy,
        createdAt,
        now);
  }

  private static Set<String> codes(Set<String> raw) {
    TreeSet<String> sorted = new TreeSet<>();
    if (raw != null) {
      for (String code : raw) {
        String normalized = upper(code);
        if (!normalized.isEmpty()) {
          sorted.add(normalized);
        }
      }
    }
    return sorted;
  }

  private static String upper(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }
}
