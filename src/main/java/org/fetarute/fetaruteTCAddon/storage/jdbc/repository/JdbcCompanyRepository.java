package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的公司仓库。 */
public final class JdbcCompanyRepository extends JdbcRepositorySupport
    implements CompanyRepository {

  public JdbcCompanyRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Company> findById(UUID id) {
    String sql =
        "SELECT id, code, name, secondary_name, owner_identity_id, status, balance_minor, metadata, created_at, updated_at FROM "
            + table("companies")
            + " WHERE id = ?";
    return querySingle(sql, id);
  }

  @Override
  public Optional<Company> findByCode(String code) {
    String sql =
        "SELECT id, code, name, secondary_name, owner_identity_id, status, balance_minor, metadata, created_at, updated_at FROM "
            + table("companies")
            + " WHERE code = ?";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 code 查询公司失败", ex);
    }
  }

  @Override
  public List<Company> listAll() {
    String sql =
        "SELECT id, code, name, secondary_name, owner_identity_id, status, balance_minor, metadata, created_at, updated_at FROM "
            + table("companies");
    List<Company> results = new ArrayList<>();
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(mapRow(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出公司失败", ex);
    }
  }

  @Override
  public List<Company> listByOwner(UUID ownerIdentityId) {
    String sql =
        "SELECT id, code, name, secondary_name, owner_identity_id, status, balance_minor, metadata, created_at, updated_at FROM "
            + table("companies")
            + " WHERE owner_identity_id = ?";
    List<Company> results = new ArrayList<>();
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, ownerIdentityId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("按 owner 查询公司失败", ex);
    }
  }

  @Override
  public Company save(Company company) {
    String insert =
        "INSERT INTO "
            + table("companies")
            + " (id, code, name, secondary_name, owner_identity_id, status, balance_minor, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    // 主键冲突时覆盖业务字段，code 仍受唯一索引约束
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "code",
                "name",
                "secondary_name",
                "owner_identity_id",
                "status",
                "balance_minor",
                "metadata",
                "updated_at"));
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      writeCompany(statement, company);
      statement.executeUpdate();
      commitIfNecessary(connection);
      return company;
    } catch (SQLException ex) {
      throw new StorageException("保存公司失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("companies") + " WHERE id = ?";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      commitIfNecessary(connection);
    } catch (SQLException ex) {
      throw new StorageException("删除公司失败", ex);
    }
  }

  private Optional<Company> querySingle(String sql, UUID id) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询公司失败", ex);
    }
  }

  private void writeCompany(java.sql.PreparedStatement statement, Company company)
      throws SQLException {
    setUuid(statement, 1, company.id());
    statement.setString(2, company.code());
    statement.setString(3, company.name());
    statement.setString(4, company.secondaryName().orElse(null));
    setUuid(statement, 5, company.ownerIdentityId());
    statement.setString(6, company.status().name());
    statement.setLong(7, company.balanceMinor());
    statement.setString(8, toJson(company.metadata()));
    statement.setTimestamp(9, toTimestamp(company.createdAt()));
    statement.setTimestamp(10, toTimestamp(company.updatedAt()));
  }

  private Company mapRow(ResultSet rs) throws SQLException {
    UUID id = readUuid(rs, "id");
    String code = rs.getString("code");
    String name = rs.getString("name");
    String secondaryName = rs.getString("secondary_name");
    UUID ownerId = readUuid(rs, "owner_identity_id");
    CompanyStatus status = CompanyStatus.valueOf(rs.getString("status"));
    long balanceMinor = rs.getLong("balance_minor");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = fromTimestamp(rs.getTimestamp("created_at"));
    Instant updatedAt = fromTimestamp(rs.getTimestamp("updated_at"));
    return new Company(
        id,
        code,
        name,
        Optional.ofNullable(secondaryName),
        ownerId,
        status,
        balanceMinor,
        metadata,
        createdAt,
        updatedAt);
  }
}
