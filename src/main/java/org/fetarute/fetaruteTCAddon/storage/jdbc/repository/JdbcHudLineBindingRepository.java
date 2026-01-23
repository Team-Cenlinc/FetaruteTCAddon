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
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudLineBindingRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的线路 HUD 模板绑定仓库。 */
public final class JdbcHudLineBindingRepository extends JdbcRepositorySupport
    implements HudLineBindingRepository {

  public JdbcHudLineBindingRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<LineBinding> findByLineAndType(UUID lineId, HudTemplateType type) {
    String sql =
        "SELECT line_id, template_type, template_id, updated_at FROM "
            + table("hud_line_bindings")
            + " WHERE line_id = ? AND template_type = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, lineId);
      statement.setString(2, type.name());
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询线路 HUD 模板绑定失败", ex);
    }
  }

  @Override
  public List<LineBinding> listByLine(UUID lineId) {
    String sql =
        "SELECT line_id, template_type, template_id, updated_at FROM "
            + table("hud_line_bindings")
            + " WHERE line_id = ? ORDER BY template_type ASC";
    List<LineBinding> results = new ArrayList<>();
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
      throw new StorageException("列出线路 HUD 模板绑定失败", ex);
    }
  }

  @Override
  public List<LineBinding> listAll() {
    String sql =
        "SELECT line_id, template_type, template_id, updated_at FROM "
            + table("hud_line_bindings")
            + " ORDER BY line_id ASC, template_type ASC";
    List<LineBinding> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(mapRow(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出线路 HUD 模板绑定失败", ex);
    }
  }

  @Override
  public LineBinding save(LineBinding binding) {
    Objects.requireNonNull(binding, "binding");
    String insert =
        "INSERT INTO "
            + table("hud_line_bindings")
            + " (line_id, template_type, template_id, updated_at)"
            + " VALUES (?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert, List.of("line_id", "template_type"), List.of("template_id", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeBinding(statement, binding);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return binding;
    } catch (SQLException ex) {
      throw new StorageException("保存线路 HUD 模板绑定失败", ex);
    }
  }

  @Override
  public void delete(UUID lineId, HudTemplateType type) {
    String sql =
        "DELETE FROM " + table("hud_line_bindings") + " WHERE line_id = ? AND template_type = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, lineId);
      statement.setString(2, type.name());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除线路 HUD 模板绑定失败", ex);
    }
  }

  private void writeBinding(java.sql.PreparedStatement statement, LineBinding binding)
      throws SQLException {
    setUuid(statement, 1, binding.lineId());
    statement.setString(2, binding.type().name());
    setUuid(statement, 3, binding.templateId());
    setInstant(statement, 4, binding.updatedAt());
  }

  private LineBinding mapRow(ResultSet rs) throws SQLException {
    UUID lineId = requireUuid(rs, "line_id");
    String type = rs.getString("template_type");
    UUID templateId = requireUuid(rs, "template_id");
    Instant updatedAt = readInstant(rs, "updated_at");
    return new LineBinding(lineId, HudTemplateType.valueOf(type), templateId, updatedAt);
  }
}
