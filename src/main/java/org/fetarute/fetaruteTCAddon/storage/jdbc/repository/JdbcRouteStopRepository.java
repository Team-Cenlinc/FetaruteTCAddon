package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的 RouteStop 仓库。
 *
 * <p>RouteStop 使用 (routeId, sequence) 作为主键，保证同一路线内顺序唯一；写入时通常采用“整表替换”策略： 先 {@code
 * deleteAll(routeId)} 再按 sequence 递增写入。
 */
public final class JdbcRouteStopRepository extends JdbcRepositorySupport
    implements RouteStopRepository {

  public JdbcRouteStopRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<RouteStop> listByRoute(UUID routeId) {
    String sql =
        "SELECT route_id, sequence, station_id, waypoint_node_id, dwell_secs, pass_type, notes FROM "
            + table("route_stops")
            + " WHERE route_id = ?"
            + " ORDER BY sequence ASC";
    List<RouteStop> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, routeId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出 RouteStop 失败", ex);
    }
  }

  @Override
  public RouteStop save(RouteStop stop) {
    String insert =
        "INSERT INTO "
            + table("route_stops")
            + " (route_id, sequence, station_id, waypoint_node_id, dwell_secs, pass_type, notes)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("route_id", "sequence"),
            List.of("station_id", "waypoint_node_id", "dwell_secs", "pass_type", "notes"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeStop(statement, stop);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return stop;
    } catch (SQLException ex) {
      throw new StorageException("保存 RouteStop 失败", ex);
    }
  }

  @Override
  public void delete(UUID routeId, int sequence) {
    String sql = "DELETE FROM " + table("route_stops") + " WHERE route_id = ? AND sequence = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, routeId);
      statement.setInt(2, sequence);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 RouteStop 失败", ex);
    }
  }

  @Override
  public void deleteAll(UUID routeId) {
    String sql = "DELETE FROM " + table("route_stops") + " WHERE route_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, routeId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("清空 RouteStop 失败", ex);
    }
  }

  private void writeStop(java.sql.PreparedStatement statement, RouteStop stop) throws SQLException {
    setUuid(statement, 1, stop.routeId());
    statement.setInt(2, stop.sequence());
    setUuid(statement, 3, stop.stationId().orElse(null));
    statement.setString(4, stop.waypointNodeId().orElse(null));
    if (stop.dwellSeconds().isPresent()) {
      statement.setInt(5, stop.dwellSeconds().get());
    } else {
      statement.setObject(5, null);
    }
    statement.setString(6, stop.passType().name());
    statement.setString(7, stop.notes().orElse(null));
  }

  private RouteStop mapRow(ResultSet rs) throws SQLException {
    UUID routeId = requireUuid(rs, "route_id");
    int sequence = rs.getInt("sequence");
    UUID stationId = readUuid(rs, "station_id");
    String waypointNodeId = rs.getString("waypoint_node_id");
    Integer dwell = readNullableInteger(rs, "dwell_secs");
    String passType = rs.getString("pass_type");
    String notes = rs.getString("notes");
    return new RouteStop(
        routeId,
        sequence,
        Optional.ofNullable(stationId),
        Optional.ofNullable(waypointNodeId),
        Optional.ofNullable(dwell),
        RouteStopPassType.valueOf(passType),
        Optional.ofNullable(notes));
  }
}
