package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;

/**
 * 输入快照的内容指纹：决定静态包缓存到期时要不要真的重建。
 *
 * <p>只读快照、不生成 CSV/zip，按 64 位 FNV-1a 流式混合。实服规模（2666 班、约 2.4 万条停站）约 0.2 ms，缓存到期时比完整构建（约 0.15–1
 * 秒）便宜两个数量级。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>覆盖所有会影响输出 CSV 的输入：运营商/线路/车站/车站组/股道、时刻表车次与站间时分、交路停靠表（{@code CHANGE}）、计划股道版本；
 *   <li>按内容而不是“重新加载次数”判断：60 秒一次的周期 reload 即使内容没变也不会换 hash；
 *   <li>{@code generatedAt} 本身不入 hash，只取它所在日历日：跨零点要滚日历窗口与版本里的日期，同一天内重复生成不应算变化；
 *   <li>无序集合/映射用可交换的求和混合，避免 {@code ConcurrentHashMap} 的迭代顺序造成误判。
 * </ul>
 *
 * <p>任何会影响 GTFS 的新输入都必须加进这里，否则内容变了也会被判成“没变”；漏加字段的兜底是每 30 分钟强制重建一次（见 {@code GtfsFeedService}）。
 */
final class GtfsSnapshotHash {

  private static final long SEED = 0xcbf29ce484222325L;

  private static final long PRIME = 0x100000001b3L;

  private GtfsSnapshotHash() {}

  /**
   * 算一份快照的内容指纹。
   *
   * @param snapshot 输入快照
   * @return 64 位指纹；相同内容稳定返回同一个值
   */
  static long of(GtfsSnapshot snapshot) {
    long h = SEED;
    h = mix(h, 1L);
    h = mix(h, snapshot.serverId());
    h = mix(h, snapshot.zone().getId());
    h = mix(h, snapshot.publisherName());
    h = mix(h, snapshot.publisherUrl());
    h = mix(h, snapshot.timetableEnabled() ? 1L : 0L);
    h = mix(h, LocalDate.ofInstant(snapshot.generatedAt(), snapshot.zone()).toEpochDay());
    h = mix(h, snapshot.platformPlansVersion());

    long operators = 0L;
    for (Operator operator : snapshot.operators()) {
      operators += elementOperator(operator);
    }
    h = mix(h, operators);

    long lines = 0L;
    for (Line line : snapshot.lines()) {
      lines += elementLine(line);
    }
    h = mix(h, lines);

    long stations = 0L;
    for (StationDirectory.StationEntry station : snapshot.stations()) {
      stations += elementStation(station);
    }
    h = mix(h, stations);

    long groups = 0L;
    for (StationDirectory.GroupEntry group : snapshot.groups()) {
      groups += elementGroup(group);
    }
    h = mix(h, groups);

    long platforms = 0L;
    for (GtfsPlatformNode platform : snapshot.platforms()) {
      platforms += elementPlatform(platform);
    }
    h = mix(h, platforms);

    h = mix(h, sumUuids(snapshot.stationsOfPublishedLines()));

    long routes = 0L;
    for (Map.Entry<UUID, GtfsRouteRef> route : snapshot.routes().entrySet()) {
      routes += elementRoute(route.getKey(), route.getValue());
    }
    h = mix(h, routes);

    long timetables = 0L;
    for (Timetable timetable : snapshot.timetables()) {
      timetables += elementTimetable(timetable);
    }
    h = mix(h, timetables);

    long headway = 0L;
    for (GtfsHeadwayRoute route : snapshot.headwayRoutes()) {
      headway += elementHeadway(route);
    }
    h = mix(h, headway);
    return h;
  }

  private static long elementHeadway(GtfsHeadwayRoute route) {
    long h = SEED;
    h = mix(h, route.routeId());
    h = mix(h, route.headwaySeconds());
    for (TimetableStop stop : route.stops()) {
      h = mix(h, stop.stopSequence());
      h = mix(h, stop.nodeId());
      h = mix(h, stop.arrivalOffsetSeconds());
      h = mix(h, stop.departureOffsetSeconds());
      h = mix(h, stop.passType().name());
    }
    return h;
  }

  private static long elementOperator(Operator operator) {
    long h = SEED;
    h = mix(h, operator.id());
    h = mix(h, operator.code());
    h = mix(h, operator.name());
    h = mix(h, operator.secondaryName());
    return h;
  }

  private static long elementLine(Line line) {
    long h = SEED;
    h = mix(h, line.id());
    h = mix(h, line.operatorId());
    h = mix(h, line.code());
    h = mix(h, line.name());
    h = mix(h, line.secondaryName());
    h = mix(h, line.serviceType().name());
    h = mix(h, line.color().orElse(""));
    h = mix(h, line.status().name());
    return h;
  }

  private static long elementStation(StationDirectory.StationEntry entry) {
    Station station = entry.station();
    long h = SEED;
    h = mix(h, station.id());
    h = mix(h, station.operatorId());
    h = mix(h, station.code());
    h = mix(h, station.name());
    h = mix(h, station.secondaryName());
    boolean located = station.location().isPresent();
    h = mix(h, located ? 1L : 0L);
    if (located) {
      h = mix(h, Double.doubleToLongBits(station.location().get().x()));
      h = mix(h, Double.doubleToLongBits(station.location().get().z()));
    }
    return h;
  }

  private static long elementGroup(StationDirectory.GroupEntry group) {
    long h = SEED;
    h = mix(h, group.group().id());
    h = mix(h, group.group().code());
    h = mix(h, group.group().name());
    long members = 0L;
    for (StationDirectory.MemberEntry member : group.members()) {
      long m = SEED;
      m = mix(m, member.member().stationId());
      m = mix(m, member.member().transferType().name());
      m = mix(m, member.member().walkSeconds().orElse(-1));
      members += m;
    }
    return mix(h, members);
  }

  private static long elementPlatform(GtfsPlatformNode platform) {
    long h = SEED;
    h = mix(h, platform.nodeId());
    h = mix(h, Double.doubleToLongBits(platform.x()));
    h = mix(h, Double.doubleToLongBits(platform.z()));
    return h;
  }

  private static long elementRoute(UUID routeId, GtfsRouteRef route) {
    long h = SEED;
    h = mix(h, routeId);
    h = mix(h, route.operatorCode());
    h = mix(h, route.lineCode());
    h = mix(h, route.routeCode());
    for (RouteStop stop : route.stops()) {
      h = mix(h, stop.sequence());
      h = mixOptionalUuid(h, stop.stationId());
      h = mix(h, stop.waypointNodeId());
      h = mix(h, stop.passType().name());
      h = mix(h, stop.notes());
    }
    return h;
  }

  private static long elementTimetable(Timetable timetable) {
    long h = SEED;
    h = mix(h, timetable.id());
    h = mix(h, timetable.operatorId());
    h = mix(h, timetable.lineId());
    h = mix(h, timetable.code());
    h = mix(h, timetable.zoneId().getId());
    for (TimetableTrip trip : timetable.trips()) {
      h = mix(h, trip.id());
      h = mix(h, trip.routeId());
      h = mix(h, trip.tripCode());
      h = mix(h, trip.departureSecondOfDay());
      h = mixOptionalUuid(h, trip.dutyId());
    }
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      h = mix(h, plan.routeId());
      h = mix(h, plan.routeCode());
      h = mix(h, plan.kind().name());
      for (TimetableStop stop : plan.stops()) {
        h = mix(h, stop.stopSequence());
        h = mix(h, stop.stationCode());
        h = mix(h, stop.nodeId());
        h = mix(h, stop.arrivalOffsetSeconds());
        h = mix(h, stop.departureOffsetSeconds());
        h = mix(h, stop.passType().name());
      }
    }
    for (VehicleDuty duty : timetable.duties()) {
      h = mix(h, duty.consist().orElse(""));
    }
    return h;
  }

  private static long sumUuids(Set<UUID> values) {
    long sum = 0L;
    for (UUID value : values) {
      sum += mix(SEED, value);
    }
    return sum;
  }

  private static long mix(long h, long value) {
    return (h ^ value) * PRIME;
  }

  private static long mix(long h, String value) {
    return mix(h, value == null ? 0 : value.hashCode());
  }

  private static long mix(long h, Optional<String> value) {
    if (value == null || value.isEmpty()) {
      return mix(h, 0L);
    }
    return mix(mix(h, 1L), value.get().hashCode());
  }

  private static long mix(long h, UUID value) {
    return mix(mix(h, value.getMostSignificantBits()), value.getLeastSignificantBits());
  }

  private static long mixOptionalUuid(long h, Optional<UUID> value) {
    if (value == null || value.isEmpty()) {
      return mix(h, 0L);
    }
    return mix(mix(h, 1L), value.get());
  }
}
