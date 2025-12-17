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
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的运营商仓库。 */
public final class JdbcOperatorRepository extends JdbcRepositorySupport
    implements OperatorRepository {

  public JdbcOperatorRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Operator> findById(UUID id) {
    String sql =
        "SELECT id, code, company_id, name, secondary_name, color_theme, priority, description, metadata, created_at, updated_at FROM "
            + table("operators")
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
      throw new StorageException("查询运营商失败", ex);
    }
  }

  @Override
  public Optional<Operator> findByCompanyAndCode(UUID companyId, String code) {
    String sql =
        "SELECT id, code, company_id, name, secondary_name, color_theme, priority, description, metadata, created_at, updated_at FROM "
            + table("operators")
            + " WHERE company_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 company+code 查询运营商失败", ex);
    }
  }

  @Override
  public List<Operator> listByCompany(UUID companyId) {
    String sql =
        "SELECT id, code, company_id, name, secondary_name, color_theme, priority, description, metadata, created_at, updated_at FROM "
            + table("operators")
            + " WHERE company_id = ?"
            + " ORDER BY priority DESC, code ASC";
    List<Operator> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出公司运营商失败", ex);
    }
  }

  @Override
  public Operator save(Operator operator) {
    Objects.requireNonNull(operator, "operator");
    String insert =
        "INSERT INTO "
            + table("operators")
            + " (id, code, company_id, name, secondary_name, color_theme, priority, description, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "code",
                "company_id",
                "name",
                "secondary_name",
                "color_theme",
                "priority",
                "description",
                "metadata",
                "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeOperator(statement, operator);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return operator;
    } catch (SQLException ex) {
      throw new StorageException("保存运营商失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("operators") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除运营商失败", ex);
    }
  }

  private void writeOperator(java.sql.PreparedStatement statement, Operator operator)
      throws SQLException {
    setUuid(statement, 1, operator.id());
    statement.setString(2, operator.code());
    setUuid(statement, 3, operator.companyId());
    statement.setString(4, operator.name());
    statement.setString(5, operator.secondaryName().orElse(null));
    statement.setString(6, operator.colorTheme().orElse(null));
    statement.setInt(7, operator.priority());
    statement.setString(8, operator.description().orElse(null));
    statement.setString(9, toJson(operator.metadata()));
    setInstant(statement, 10, operator.createdAt());
    setInstant(statement, 11, operator.updatedAt());
  }

  private Operator mapRow(ResultSet rs) throws SQLException {
    UUID id = requireUuid(rs, "id");
    String code = rs.getString("code");
    UUID companyId = requireUuid(rs, "company_id");
    String name = rs.getString("name");
    String secondaryName = rs.getString("secondary_name");
    String colorTheme = rs.getString("color_theme");
    int priority = rs.getInt("priority");
    String description = rs.getString("description");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = readInstant(rs, "created_at");
    Instant updatedAt = readInstant(rs, "updated_at");
    return new Operator(
        id,
        code,
        Objects.requireNonNull(companyId, "companyId"),
        name,
        Optional.ofNullable(secondaryName),
        Optional.ofNullable(colorTheme),
        priority,
        Optional.ofNullable(description),
        metadata,
        createdAt,
        updatedAt);
  }
}
