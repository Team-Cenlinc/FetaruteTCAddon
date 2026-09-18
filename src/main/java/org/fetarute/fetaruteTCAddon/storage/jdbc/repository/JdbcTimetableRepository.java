package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的时刻表仓库。
 *
 * <p>表头、发车表与车辆交路是同一个聚合：{@link #save(Timetable)} 在同一条连接上先覆盖表头、再整体替换 trips 与 duties。调用方若已开启事务，{@code
 * openConnection()} 会复用事务连接，因此"表头写了但车次没写" 这种半份状态不会落库。
 *
 * <p>读取同样共用一条连接：SQLite 走单连接池，持着一条连接再去 {@code openConnection()} 会一直等到池超时。
 */
public final class JdbcTimetableRepository extends JdbcRepositorySupport
    implements TimetableRepository {

  private static final Type ROUTE_PLAN_LIST_TYPE = new TypeToken<List<RoutePlanDto>>() {}.getType();
  private static final Type UUID_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

  private static final String HEADER_COLUMNS =
      "id, company_id, operator_id, line_id, code, name, status, zone_id,"
          + " service_start_second, service_end_second, route_plans, notes, created_at, updated_at";

  private static final String TRIP_COLUMNS =
      "id, timetable_id, route_id, duty_id, sequence, trip_code, departure_second_of_day";

  private static final String DUTY_COLUMNS =
      "id, timetable_id, sequence, duty_code, start_depot_node_id, end_depot_node_id,"
          + " create_route_id, return_route_id, trip_ids, planned_start_second, return_second,"
          + " planned_end_second, close_reason";

  public JdbcTimetableRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Timetable> findById(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    List<Timetable> found = listWhere(" WHERE id = ?", statement -> setUuid(statement, 1, id));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public Optional<Timetable> findByLineAndCode(UUID lineId, String code) {
    if (lineId == null || code == null || code.isBlank()) {
      return Optional.empty();
    }
    List<Timetable> found =
        listWhere(
            " WHERE line_id = ? AND code = ?",
            statement -> {
              setUuid(statement, 1, lineId);
              statement.setString(2, code.trim());
            });
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public List<Timetable> listByLine(UUID lineId) {
    if (lineId == null) {
      return List.of();
    }
    return listWhere(
        " WHERE line_id = ? ORDER BY code ASC", statement -> setUuid(statement, 1, lineId));
  }

  @Override
  public List<Timetable> listPublished() {
    return listWhere(
        " WHERE status = ? ORDER BY code ASC",
        statement -> statement.setString(1, TimetableStatus.PUBLISHED.name()));
  }

  @Override
  public Timetable save(Timetable timetable) {
    Objects.requireNonNull(timetable, "timetable");
    String insert =
        "INSERT INTO "
            + table("timetables")
            + " ("
            + HEADER_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String upsert =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "company_id",
                "operator_id",
                "line_id",
                "code",
                "name",
                "status",
                "zone_id",
                "service_start_second",
                "service_end_second",
                "route_plans",
                "notes",
                "updated_at"));
    try (var connection = openConnection()) {
      try (var statement = connection.prepareStatement(upsert)) {
        writeHeader(statement, timetable);
        statement.executeUpdate();
      }
      replaceChildren(connection, timetable);
      connection.commitIfNecessary();
      return timetable;
    } catch (SQLException ex) {
      throw new StorageException("保存时刻表失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    // 依赖外键级联在这里是不够的：SQLite 只有在 PRAGMA foreign_keys=ON 时才级联，
    // 而这条删除是运营命令直接触发的，必须无论方言与连接设置都把子表清干净。
    try (var connection = openConnection()) {
      deleteChildren(connection, id);
      try (var statement =
          connection.prepareStatement("DELETE FROM " + table("timetables") + " WHERE id = ?")) {
        setUuid(statement, 1, id);
        statement.executeUpdate();
      }
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除时刻表失败", ex);
    }
  }

  private void replaceChildren(ConnectionResource connection, Timetable timetable)
      throws SQLException {
    deleteChildren(connection, timetable.id());
    if (!timetable.trips().isEmpty()) {
      String sql =
          "INSERT INTO "
              + table("timetable_trips")
              + " ("
              + TRIP_COLUMNS
              + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
      try (var statement = connection.prepareStatement(sql)) {
        for (TimetableTrip trip : timetable.trips()) {
          setUuid(statement, 1, trip.id());
          setUuid(statement, 2, timetable.id());
          setUuid(statement, 3, trip.routeId());
          setUuid(statement, 4, trip.dutyId().orElse(null));
          statement.setInt(5, trip.sequence());
          statement.setString(6, trip.tripCode());
          statement.setInt(7, trip.departureSecondOfDay());
          statement.addBatch();
        }
        statement.executeBatch();
      }
    }
    if (!timetable.duties().isEmpty()) {
      String sql =
          "INSERT INTO "
              + table("timetable_duties")
              + " ("
              + DUTY_COLUMNS
              + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
      try (var statement = connection.prepareStatement(sql)) {
        for (VehicleDuty duty : timetable.duties()) {
          setUuid(statement, 1, duty.id());
          setUuid(statement, 2, timetable.id());
          statement.setInt(3, duty.sequence());
          statement.setString(4, duty.dutyCode());
          statement.setString(5, duty.startDepotNodeId());
          statement.setString(6, duty.endDepotNodeId());
          setUuid(statement, 7, duty.createRouteId().orElse(null));
          setUuid(statement, 8, duty.returnRouteId().orElse(null));
          statement.setString(9, gson.toJson(duty.tripIds().stream().map(UUID::toString).toList()));
          statement.setInt(10, duty.plannedStartSecondOfDay());
          statement.setInt(11, duty.returnSecondOfDay());
          statement.setInt(12, duty.plannedEndSecondOfDay());
          statement.setString(13, duty.closeReason().name());
          statement.addBatch();
        }
        statement.executeBatch();
      }
    }
  }

  private void deleteChildren(ConnectionResource connection, UUID timetableId) throws SQLException {
    for (String child : List.of("timetable_trips", "timetable_duties")) {
      try (var statement =
          connection.prepareStatement("DELETE FROM " + table(child) + " WHERE timetable_id = ?")) {
        setUuid(statement, 1, timetableId);
        statement.executeUpdate();
      }
    }
  }

  /** 读表头并在<b>同一条连接</b>上补齐发车表与交路，避免 SQLite 单连接池自锁，也顺带避免 N+1。 */
  private List<Timetable> listWhere(String whereClause, StatementBinder binder) {
    String headerSql = "SELECT " + HEADER_COLUMNS + " FROM " + table("timetables") + whereClause;
    List<Timetable> headers = new ArrayList<>();
    try (var connection = openConnection()) {
      try (var statement = connection.prepareStatement(headerSql)) {
        binder.bind(statement);
        try (var rs = statement.executeQuery()) {
          while (rs.next()) {
            headers.add(mapHeader(rs));
          }
        }
      }
      if (headers.isEmpty()) {
        return List.of();
      }
      Map<UUID, List<TimetableTrip>> tripsByTable = new LinkedHashMap<>();
      Map<UUID, List<VehicleDuty>> dutiesByTable = new LinkedHashMap<>();
      String tripSql =
          "SELECT "
              + TRIP_COLUMNS
              + " FROM "
              + table("timetable_trips")
              + " WHERE timetable_id = ? ORDER BY departure_second_of_day ASC, trip_code ASC";
      String dutySql =
          "SELECT "
              + DUTY_COLUMNS
              + " FROM "
              + table("timetable_duties")
              + " WHERE timetable_id = ? ORDER BY planned_start_second ASC, duty_code ASC";
      try (var tripStatement = connection.prepareStatement(tripSql);
          var dutyStatement = connection.prepareStatement(dutySql)) {
        for (Timetable header : headers) {
          setUuid(tripStatement, 1, header.id());
          List<TimetableTrip> trips = new ArrayList<>();
          try (var rs = tripStatement.executeQuery()) {
            while (rs.next()) {
              trips.add(mapTrip(rs));
            }
          }
          tripsByTable.put(header.id(), List.copyOf(trips));

          setUuid(dutyStatement, 1, header.id());
          List<VehicleDuty> duties = new ArrayList<>();
          try (var rs = dutyStatement.executeQuery()) {
            while (rs.next()) {
              duties.add(mapDuty(rs));
            }
          }
          dutiesByTable.put(header.id(), List.copyOf(duties));
        }
      }
      List<Timetable> out = new ArrayList<>(headers.size());
      for (Timetable header : headers) {
        out.add(
            header.withTripsAndDuties(
                tripsByTable.getOrDefault(header.id(), List.of()),
                dutiesByTable.getOrDefault(header.id(), List.of())));
      }
      return List.copyOf(out);
    } catch (SQLException ex) {
      throw new StorageException("列出时刻表失败", ex);
    }
  }

  private void writeHeader(PreparedStatement statement, Timetable timetable) throws SQLException {
    setUuid(statement, 1, timetable.id());
    setUuid(statement, 2, timetable.companyId());
    setUuid(statement, 3, timetable.operatorId());
    setUuid(statement, 4, timetable.lineId());
    statement.setString(5, timetable.code());
    statement.setString(6, timetable.name());
    statement.setString(7, timetable.status().name());
    statement.setString(8, timetable.zoneId().getId());
    statement.setInt(9, timetable.serviceStartSecondOfDay());
    statement.setInt(10, timetable.serviceEndSecondOfDay());
    statement.setString(11, encodeRoutePlans(timetable.routePlans()));
    statement.setString(12, timetable.notes().orElse(null));
    setInstant(statement, 13, timetable.createdAt());
    setInstant(statement, 14, timetable.updatedAt());
  }

  private Timetable mapHeader(ResultSet rs) throws SQLException {
    String rawStatus = rs.getString("status");
    TimetableStatus status =
        TimetableStatus.parse(rawStatus)
            .orElseThrow(() -> new StorageException("无法识别的时刻表状态: " + rawStatus));
    return new Timetable(
        requireUuid(rs, "id"),
        requireUuid(rs, "company_id"),
        requireUuid(rs, "operator_id"),
        requireUuid(rs, "line_id"),
        rs.getString("code"),
        rs.getString("name"),
        status,
        parseZone(rs.getString("zone_id")),
        readRequiredInt(rs, "service_start_second"),
        readRequiredInt(rs, "service_end_second"),
        decodeRoutePlans(rs.getString("route_plans")),
        List.of(),
        List.of(),
        Optional.ofNullable(rs.getString("notes")),
        readInstant(rs, "created_at"),
        readInstant(rs, "updated_at"));
  }

  private TimetableTrip mapTrip(ResultSet rs) throws SQLException {
    return new TimetableTrip(
        requireUuid(rs, "id"),
        requireUuid(rs, "timetable_id"),
        requireUuid(rs, "route_id"),
        readRequiredInt(rs, "sequence"),
        rs.getString("trip_code"),
        readRequiredInt(rs, "departure_second_of_day"),
        Optional.ofNullable(readUuid(rs, "duty_id")));
  }

  private VehicleDuty mapDuty(ResultSet rs) throws SQLException {
    String rawReason = rs.getString("close_reason");
    VehicleDuty.CloseReason reason =
        VehicleDuty.CloseReason.parse(rawReason)
            .orElseThrow(() -> new StorageException("无法识别的 duty 结束原因: " + rawReason));
    List<String> rawTripIds = gson.fromJson(rs.getString("trip_ids"), UUID_LIST_TYPE);
    List<UUID> tripIds = new ArrayList<>();
    if (rawTripIds != null) {
      for (String raw : rawTripIds) {
        if (raw != null && !raw.isBlank()) {
          tripIds.add(UUID.fromString(raw.trim()));
        }
      }
    }
    return new VehicleDuty(
        requireUuid(rs, "id"),
        requireUuid(rs, "timetable_id"),
        readRequiredInt(rs, "sequence"),
        rs.getString("duty_code"),
        rs.getString("start_depot_node_id"),
        rs.getString("end_depot_node_id"),
        Optional.ofNullable(readUuid(rs, "create_route_id")),
        Optional.ofNullable(readUuid(rs, "return_route_id")),
        tripIds,
        readRequiredInt(rs, "planned_start_second"),
        readRequiredInt(rs, "return_second"),
        readRequiredInt(rs, "planned_end_second"),
        reason);
  }

  private int readRequiredInt(ResultSet rs, String column) throws SQLException {
    Integer value = readNullableInteger(rs, column);
    if (value == null) {
      throw new StorageException("整数列缺失: " + column);
    }
    return value;
  }

  /** 时区解析失败时直接失败：静默回落到服务器默认时区会让整张表的时刻悄悄偏移几个小时。 */
  private static ZoneId parseZone(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new StorageException("时刻表缺少 zone_id");
    }
    try {
      return ZoneId.of(raw.trim());
    } catch (DateTimeException ex) {
      throw new StorageException("无法识别的时区: " + raw, ex);
    }
  }

  private String encodeRoutePlans(List<TimetableRoutePlan> plans) {
    List<RoutePlanDto> dtos = new ArrayList<>(plans.size());
    for (TimetableRoutePlan plan : plans) {
      List<StopDto> stops = new ArrayList<>(plan.stops().size());
      for (TimetableStop stop : plan.stops()) {
        stops.add(
            new StopDto(
                stop.stopSequence(),
                stop.stationCode().orElse(null),
                stop.nodeId().orElse(null),
                stop.arrivalOffsetSeconds(),
                stop.departureOffsetSeconds()));
      }
      dtos.add(
          new RoutePlanDto(
              plan.routeId().toString(),
              plan.routeCode(),
              plan.kind().name(),
              plan.weight(),
              plan.originNodeId(),
              plan.terminalNodeId(),
              plan.depotNodeId().orElse(null),
              stops));
    }
    return gson.toJson(dtos);
  }

  private List<TimetableRoutePlan> decodeRoutePlans(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<RoutePlanDto> dtos = gson.fromJson(json, ROUTE_PLAN_LIST_TYPE);
    if (dtos == null) {
      return List.of();
    }
    List<TimetableRoutePlan> out = new ArrayList<>(dtos.size());
    for (RoutePlanDto dto : dtos) {
      if (dto == null || dto.routeId() == null) {
        continue;
      }
      List<TimetableStop> stops = new ArrayList<>();
      if (dto.stops() != null) {
        for (StopDto stop : dto.stops()) {
          if (stop == null) {
            continue;
          }
          stops.add(
              new TimetableStop(
                  stop.seq(),
                  Optional.ofNullable(stop.station()),
                  Optional.ofNullable(stop.node()),
                  stop.arr(),
                  stop.dep()));
        }
      }
      out.add(
          new TimetableRoutePlan(
              UUID.fromString(dto.routeId()),
              dto.routeCode(),
              RouteOperationType.fromToken(dto.kind()).orElse(RouteOperationType.OPERATION),
              dto.weight(),
              stops,
              dto.origin(),
              dto.terminal(),
              Optional.ofNullable(dto.depot()),
              Optional.empty()));
    }
    return List.copyOf(out);
  }

  @FunctionalInterface
  private interface StatementBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  /** route_plans 的 JSON 形状；字段名保持短，因为它会被写进每一行。 */
  private record RoutePlanDto(
      String routeId,
      String routeCode,
      String kind,
      int weight,
      String origin,
      String terminal,
      String depot,
      List<StopDto> stops) {}

  private record StopDto(int seq, String station, String node, int arr, int dep) {}
}
