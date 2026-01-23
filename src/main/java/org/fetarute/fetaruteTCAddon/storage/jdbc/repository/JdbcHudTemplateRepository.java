package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplate;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudTemplateRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 HUD 模板仓库。 */
public final class JdbcHudTemplateRepository extends JdbcRepositorySupport
    implements HudTemplateRepository {

  public JdbcHudTemplateRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<HudTemplate> findById(UUID id) {
    String sql =
        "SELECT id, company_id, type, name, content, created_at, updated_at FROM "
            + table("hud_templates")
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
      throw new StorageException("查询 HUD 模板失败", ex);
    }
  }

  @Override
  public Optional<HudTemplate> findByCompanyAndName(
      UUID companyId, HudTemplateType type, String name) {
    String sql =
        "SELECT id, company_id, type, name, content, created_at, updated_at FROM "
            + table("hud_templates")
            + " WHERE company_id = ? AND type = ? AND name = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      statement.setString(2, type.name());
      statement.setString(3, name);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 company+type+name 查询 HUD 模板失败", ex);
    }
  }

  @Override
  public List<HudTemplate> listByCompany(UUID companyId) {
    String sql =
        "SELECT id, company_id, type, name, content, created_at, updated_at FROM "
            + table("hud_templates")
            + " WHERE company_id = ? ORDER BY type ASC, name ASC";
    List<HudTemplate> results = new ArrayList<>();
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
      throw new StorageException("列出 HUD 模板失败", ex);
    }
  }

  @Override
  public List<HudTemplate> listByCompanyAndType(UUID companyId, HudTemplateType type) {
    String sql =
        "SELECT id, company_id, type, name, content, created_at, updated_at FROM "
            + table("hud_templates")
            + " WHERE company_id = ? AND type = ? ORDER BY name ASC";
    List<HudTemplate> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      statement.setString(2, type.name());
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出 HUD 模板失败", ex);
    }
  }

  @Override
  public HudTemplate save(HudTemplate template) {
    Objects.requireNonNull(template, "template");
    String insert =
        "INSERT INTO "
            + table("hud_templates")
            + " (id, company_id, type, name, content, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert, List.of("id"), List.of("company_id", "type", "name", "content", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeTemplate(statement, template);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return template;
    } catch (SQLException ex) {
      throw new StorageException("保存 HUD 模板失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("hud_templates") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 HUD 模板失败", ex);
    }
  }

  private void writeTemplate(java.sql.PreparedStatement statement, HudTemplate template)
      throws SQLException {
    setUuid(statement, 1, template.id());
    setUuid(statement, 2, template.companyId());
    statement.setString(3, template.type().name());
    statement.setString(4, template.name());
    statement.setString(5, template.content());
    setInstant(statement, 6, template.createdAt());
    setInstant(statement, 7, template.updatedAt());
  }

  private HudTemplate mapRow(ResultSet rs) throws SQLException {
    UUID id = requireUuid(rs, "id");
    UUID companyId = requireUuid(rs, "company_id");
    String type = rs.getString("type");
    String name = rs.getString("name");
    String content = rs.getString("content");
    Instant createdAt = readInstant(rs, "created_at");
    Instant updatedAt = readInstant(rs, "updated_at");
    return new HudTemplate(
        id, companyId, HudTemplateType.valueOf(type), name, content, createdAt, updatedAt);
  }
}
