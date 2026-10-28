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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBinding;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的运行计划书仓库：计划书表 {@code timetable_plans} 与绑定表 {@code timetable_plan_bindings}。 */
public final class JdbcTimetablePlanRepository extends JdbcRepositorySupport
    implements TimetablePlanRepository {

  private static final String PLAN_COLUMNS =
      "id, operator_id, code, name, body, created_at, updated_at";
  private static final String BINDING_COLUMNS = "operator_id, timetable_code, plan_id";

  public JdbcTimetablePlanRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<TimetablePlan> findById(UUID id) {
    String sql = "SELECT " + PLAN_COLUMNS + " FROM " + table("timetable_plans") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      return readPlans(statement).stream().findFirst();
    } catch (SQLException ex) {
      throw new StorageException("查询运行计划书失败", ex);
    }
  }

  @Override
  public Optional<TimetablePlan> findByOperatorAndCode(UUID operatorId, String code) {
    String sql =
        "SELECT "
            + PLAN_COLUMNS
            + " FROM "
            + table("timetable_plans")
            + " WHERE operator_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, code == null ? "" : code.trim());
      return readPlans(statement).stream().findFirst();
    } catch (SQLException ex) {
      throw new StorageException("按运营商与 code 查询运行计划书失败", ex);
    }
  }

  @Override
  public List<TimetablePlan> listByOperator(UUID operatorId) {
    String sql =
        "SELECT "
            + PLAN_COLUMNS
            + " FROM "
            + table("timetable_plans")
            + " WHERE operator_id = ? ORDER BY code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      return readPlans(statement);
    } catch (SQLException ex) {
      throw new StorageException("列出运行计划书失败", ex);
    }
  }

  @Override
  public TimetablePlan save(TimetablePlan plan) {
    Objects.requireNonNull(plan, "plan");
    String insert =
        "INSERT INTO "
            + table("timetable_plans")
            + " ("
            + PLAN_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert, List.of("id"), List.of("operator_id", "code", "name", "body", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, plan.id());
      setUuid(statement, 2, plan.operatorId());
      statement.setString(3, plan.code());
      statement.setString(4, plan.name());
      statement.setString(5, plan.body());
      setInstant(statement, 6, plan.createdAt());
      setInstant(statement, 7, plan.updatedAt());
      statement.executeUpdate();
      connection.commitIfNecessary();
      return plan;
    } catch (SQLException ex) {
      throw new StorageException("保存运行计划书失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("timetable_plans") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除运行计划书失败", ex);
    }
  }

  @Override
  public Optional<TimetablePlanBinding> findBinding(UUID operatorId, String timetableCode) {
    String sql =
        "SELECT "
            + BINDING_COLUMNS
            + " FROM "
            + table("timetable_plan_bindings")
            + " WHERE operator_id = ? AND timetable_code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, timetableCode == null ? "" : timetableCode.trim());
      return readBindings(statement).stream().findFirst();
    } catch (SQLException ex) {
      throw new StorageException("查询运行计划书绑定失败", ex);
    }
  }

  @Override
  public List<TimetablePlanBinding> listBindingsByOperator(UUID operatorId) {
    return listBindings("operator_id", operatorId);
  }

  @Override
  public List<TimetablePlanBinding> listBindingsByPlan(UUID planId) {
    return listBindings("plan_id", planId);
  }

  @Override
  public void bind(TimetablePlanBinding binding) {
    Objects.requireNonNull(binding, "binding");
    String insert =
        "INSERT INTO "
            + table("timetable_plan_bindings")
            + " ("
            + BINDING_COLUMNS
            + ") VALUES (?, ?, ?)";
    String sql =
        dialect.applyUpsert(insert, List.of("operator_id", "timetable_code"), List.of("plan_id"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, binding.operatorId());
      statement.setString(2, binding.timetableCode());
      setUuid(statement, 3, binding.planId());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("绑定运行计划书失败", ex);
    }
  }

  @Override
  public boolean unbind(UUID operatorId, String timetableCode) {
    String sql =
        "DELETE FROM "
            + table("timetable_plan_bindings")
            + " WHERE operator_id = ? AND timetable_code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, timetableCode == null ? "" : timetableCode.trim());
      int removed = statement.executeUpdate();
      connection.commitIfNecessary();
      return removed > 0;
    } catch (SQLException ex) {
      throw new StorageException("解除运行计划书绑定失败", ex);
    }
  }

  private List<TimetablePlanBinding> listBindings(String column, UUID value) {
    String sql =
        "SELECT "
            + BINDING_COLUMNS
            + " FROM "
            + table("timetable_plan_bindings")
            + " WHERE "
            + column
            + " = ? ORDER BY timetable_code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, value);
      return readBindings(statement);
    } catch (SQLException ex) {
      throw new StorageException("列出运行计划书绑定失败", ex);
    }
  }

  private List<TimetablePlan> readPlans(PreparedStatement statement) throws SQLException {
    List<TimetablePlan> results = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(
            new TimetablePlan(
                requireUuid(rs, "id"),
                requireUuid(rs, "operator_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("body"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")));
      }
    }
    return results;
  }

  private List<TimetablePlanBinding> readBindings(PreparedStatement statement) throws SQLException {
    List<TimetablePlanBinding> results = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(
            new TimetablePlanBinding(
                requireUuid(rs, "operator_id"),
                rs.getString("timetable_code"),
                requireUuid(rs, "plan_id")));
      }
    }
    return results;
  }
}
