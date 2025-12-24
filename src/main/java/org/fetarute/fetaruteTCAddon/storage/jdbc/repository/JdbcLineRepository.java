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
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的线路仓库。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li>SQL 使用 {@code tablePrefix + 表名} 动态组装，prefix 来自配置且表名来自白名单（代码内固定）。
 *   <li>写入采用 dialect 的 upsert 语法，避免手动区分 insert/update。
 *   <li>连接提交通过 {@code commitIfNecessary()} 统一处理，兼容事务连接复用。
 * </ul>
 */
public final class JdbcLineRepository extends JdbcRepositorySupport implements LineRepository {

  public JdbcLineRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Line> findById(UUID id) {
    String sql =
        "SELECT id, code, operator_id, name, secondary_name, service_type, color, status, spawn_freq_baseline_sec, metadata, created_at, updated_at FROM "
            + table("lines")
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
      throw new StorageException("查询线路失败", ex);
    }
  }

  @Override
  public Optional<Line> findByOperatorAndCode(UUID operatorId, String code) {
    String sql =
        "SELECT id, code, operator_id, name, secondary_name, service_type, color, status, spawn_freq_baseline_sec, metadata, created_at, updated_at FROM "
            + table("lines")
            + " WHERE operator_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 operator+code 查询线路失败", ex);
    }
  }

  @Override
  public List<Line> listByOperator(UUID operatorId) {
    String sql =
        "SELECT id, code, operator_id, name, secondary_name, service_type, color, status, spawn_freq_baseline_sec, metadata, created_at, updated_at FROM "
            + table("lines")
            + " WHERE operator_id = ?"
            + " ORDER BY code ASC";
    List<Line> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出线路失败", ex);
    }
  }

  @Override
  public Line save(Line line) {
    Objects.requireNonNull(line, "line");
    String insert =
        "INSERT INTO "
            + table("lines")
            + " (id, code, operator_id, name, secondary_name, service_type, color, status, spawn_freq_baseline_sec, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "code",
                "operator_id",
                "name",
                "secondary_name",
                "service_type",
                "color",
                "status",
                "spawn_freq_baseline_sec",
                "metadata",
                "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeLine(statement, line);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return line;
    } catch (SQLException ex) {
      throw new StorageException("保存线路失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("lines") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除线路失败", ex);
    }
  }

  private void writeLine(java.sql.PreparedStatement statement, Line line) throws SQLException {
    setUuid(statement, 1, line.id());
    statement.setString(2, line.code());
    setUuid(statement, 3, line.operatorId());
    statement.setString(4, line.name());
    statement.setString(5, line.secondaryName().orElse(null));
    statement.setString(6, line.serviceType().name());
    statement.setString(7, line.color().orElse(null));
    statement.setString(8, line.status().name());
    if (line.spawnFreqBaselineSec().isPresent()) {
      statement.setInt(9, line.spawnFreqBaselineSec().get());
    } else {
      statement.setObject(9, null);
    }
    statement.setString(10, toJson(line.metadata()));
    setInstant(statement, 11, line.createdAt());
    setInstant(statement, 12, line.updatedAt());
  }

  private Line mapRow(ResultSet rs) throws SQLException {
    UUID id = requireUuid(rs, "id");
    String code = rs.getString("code");
    UUID operatorId = requireUuid(rs, "operator_id");
    String name = rs.getString("name");
    String secondaryName = rs.getString("secondary_name");
    String serviceType = rs.getString("service_type");
    String color = rs.getString("color");
    String status = rs.getString("status");
    Integer spawnFreq = readNullableInteger(rs, "spawn_freq_baseline_sec");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = readInstant(rs, "created_at");
    Instant updatedAt = readInstant(rs, "updated_at");
    return new Line(
        id,
        code,
        operatorId,
        name,
        Optional.ofNullable(secondaryName),
        LineServiceType.valueOf(serviceType),
        Optional.ofNullable(color),
        LineStatus.valueOf(status),
        Optional.ofNullable(spawnFreq),
        metadata,
        createdAt,
        updatedAt);
  }
}
