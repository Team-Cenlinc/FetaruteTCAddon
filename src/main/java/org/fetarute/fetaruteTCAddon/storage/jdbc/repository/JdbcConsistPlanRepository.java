package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlanRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的编组方案仓库。 */
public final class JdbcConsistPlanRepository extends JdbcRepositorySupport
    implements ConsistPlanRepository {

  private static final String COLUMNS = "id, operator_id, name, body, created_at, updated_at";

  public JdbcConsistPlanRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<ConsistPlan> findById(UUID id) {
    String sql = "SELECT " + COLUMNS + " FROM " + table("consist_plans") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      return readOne(statement);
    } catch (SQLException ex) {
      throw new StorageException("查询编组方案失败", ex);
    }
  }

  @Override
  public Optional<ConsistPlan> findByOperatorAndName(UUID operatorId, String name) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM "
            + table("consist_plans")
            + " WHERE operator_id = ? AND name_key = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, ConsistPlan.nameKey(name));
      return readOne(statement);
    } catch (SQLException ex) {
      throw new StorageException("按运营商与名字查询编组方案失败", ex);
    }
  }

  @Override
  public List<ConsistPlan> listByOperator(UUID operatorId) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM "
            + table("consist_plans")
            + " WHERE operator_id = ? ORDER BY name_key ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      return readAll(statement);
    } catch (SQLException ex) {
      throw new StorageException("列出编组方案失败", ex);
    }
  }

  @Override
  public List<ConsistPlan> listAll() {
    String sql = "SELECT " + COLUMNS + " FROM " + table("consist_plans") + " ORDER BY name_key ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      return readAll(statement);
    } catch (SQLException ex) {
      throw new StorageException("列出编组方案失败", ex);
    }
  }

  @Override
  public ConsistPlan save(ConsistPlan plan) {
    Objects.requireNonNull(plan, "plan");
    String insert =
        "INSERT INTO "
            + table("consist_plans")
            + " (id, operator_id, name, name_key, body, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of("operator_id", "name", "name_key", "body", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, plan.id());
      setUuid(statement, 2, plan.operatorId());
      statement.setString(3, plan.name());
      statement.setString(4, plan.nameKey());
      statement.setString(5, plan.body());
      setInstant(statement, 6, plan.createdAt());
      setInstant(statement, 7, plan.updatedAt());
      statement.executeUpdate();
      connection.commitIfNecessary();
      return plan;
    } catch (SQLException ex) {
      throw new StorageException("保存编组方案失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("consist_plans") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除编组方案失败", ex);
    }
  }

  private Optional<ConsistPlan> readOne(PreparedStatement statement) throws SQLException {
    try (var rs = statement.executeQuery()) {
      return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
    }
  }

  private List<ConsistPlan> readAll(PreparedStatement statement) throws SQLException {
    List<ConsistPlan> results = new ArrayList<>();
    try (var rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(mapRow(rs));
      }
    }
    return results;
  }

  private ConsistPlan mapRow(ResultSet rs) throws SQLException {
    return new ConsistPlan(
        requireUuid(rs, "id"),
        requireUuid(rs, "operator_id"),
        rs.getString("name"),
        rs.getString("body"),
        readInstant(rs, "created_at"),
        readInstant(rs, "updated_at"));
  }
}
