package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的 Route 仓库。
 *
 * <p>Route 的 code 在 line 范围内唯一（由 schema 唯一索引保证），此仓库提供按 (lineId, code) 查询的常用路径， 供命令层与调度层快速定位运行图。
 */
public final class JdbcRouteRepository extends JdbcRepositorySupport implements RouteRepository {

  public JdbcRouteRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Route> findById(UUID id) {
    String sql =
        "SELECT id, code, line_id, name, secondary_name, pattern_type, operation_type, distance_m, runtime_secs, metadata, created_at, updated_at FROM "
            + table("routes")
            + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询 Route 失败", ex);
    }
  }

  @Override
  public Optional<Route> findByLineAndCode(UUID lineId, String code) {
    String sql =
        "SELECT id, code, line_id, name, secondary_name, pattern_type, operation_type, distance_m, runtime_secs, metadata, created_at, updated_at FROM "
            + table("routes")
            + " WHERE line_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, lineId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 line+code 查询 Route 失败", ex);
    }
  }

  @Override
  public List<Route> listByLine(UUID lineId) {
    String sql =
        "SELECT id, code, line_id, name, secondary_name, pattern_type, operation_type, distance_m, runtime_secs, metadata, created_at, updated_at FROM "
            + table("routes")
            + " WHERE line_id = ?"
            + " ORDER BY code ASC";
    List<Route> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, lineId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出 Route 失败", ex);
    }
  }

  @Override
  public Route save(Route route) {
    Objects.requireNonNull(route, "route");
    String insert =
        "INSERT INTO "
            + table("routes")
            + " (id, code, line_id, name, secondary_name, pattern_type, operation_type, distance_m, runtime_secs, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "code",
                "line_id",
                "name",
                "secondary_name",
                "pattern_type",
                "operation_type",
                "distance_m",
                "runtime_secs",
                "metadata",
                "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeRoute(statement, route);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return route;
    } catch (SQLException ex) {
      throw new StorageException("保存 Route 失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("routes") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 Route 失败", ex);
    }
  }

  private void writeRoute(java.sql.PreparedStatement statement, Route route) throws SQLException {
    setUuid(statement, 1, route.id());
    statement.setString(2, route.code());
    setUuid(statement, 3, route.lineId());
    statement.setString(4, route.name());
    statement.setString(5, route.secondaryName().orElse(null));
    statement.setString(6, route.patternType().name());
    statement.setString(7, route.operationType().name());
    if (route.distanceMeters().isPresent()) {
      statement.setInt(8, route.distanceMeters().get());
    } else {
      statement.setObject(8, null);
    }
    if (route.runtimeSeconds().isPresent()) {
      statement.setInt(9, route.runtimeSeconds().get());
    } else {
      statement.setObject(9, null);
    }
    statement.setString(10, toJson(route.metadata()));
    setInstant(statement, 11, route.createdAt());
    setInstant(statement, 12, route.updatedAt());
  }

  private Route mapRow(ResultSet rs) throws SQLException {
    UUID id = requireUuid(rs, "id");
    String code = rs.getString("code");
    UUID lineId = requireUuid(rs, "line_id");
    String name = rs.getString("name");
    String secondaryName = rs.getString("secondary_name");
    String patternType = rs.getString("pattern_type");
    String operationType = rs.getString("operation_type");
    Integer distanceMeters = readNullableInteger(rs, "distance_m");
    Integer runtimeSeconds = readNullableInteger(rs, "runtime_secs");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = readInstant(rs, "created_at");
    Instant updatedAt = readInstant(rs, "updated_at");
    Optional<RoutePatternType> parsedPattern = RoutePatternType.fromToken(patternType);
    if (parsedPattern.isEmpty()) {
      throw new StorageException("无效的 RoutePatternType: " + patternType);
    }
    Optional<RouteOperationType> parsedOperation = RouteOperationType.fromToken(operationType);
    if (parsedOperation.isEmpty()) {
      throw new StorageException("无效的 RouteOperationType: " + operationType);
    }
    return new Route(
        id,
        code,
        lineId,
        name,
        Optional.ofNullable(secondaryName),
        parsedPattern.get(),
        parsedOperation.get(),
        Optional.ofNullable(distanceMeters),
        Optional.ofNullable(runtimeSeconds),
        metadata,
        createdAt,
        updatedAt);
  }
}
