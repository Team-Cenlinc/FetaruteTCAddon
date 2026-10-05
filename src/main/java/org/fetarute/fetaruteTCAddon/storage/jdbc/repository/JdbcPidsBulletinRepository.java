package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.repository.PidsBulletinRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的站台屏公告仓库。车站与线路清单各存为一个 JSON 字符串数组。 */
public final class JdbcPidsBulletinRepository extends JdbcRepositorySupport
    implements PidsBulletinRepository {

  private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

  private static final String COLUMNS =
      "id, company_id, operator_code, station_codes, line_codes, level, title, title_secondary,"
          + " body, body_secondary, starts_at, ends_at, created_by, created_at, updated_at";

  public JdbcPidsBulletinRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<PidsBulletin> listAll() {
    String sql = "SELECT " + COLUMNS + " FROM " + table("pids_bulletins") + " ORDER BY created_at";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<PidsBulletin> bulletins = new ArrayList<>();
      while (rs.next()) {
        bulletins.add(map(rs));
      }
      return bulletins;
    } catch (SQLException ex) {
      throw new StorageException("列出站台屏公告失败", ex);
    }
  }

  /** 先按编号更新，没有这一行再插入（与站台屏仓库同一写法，不依赖方言 upsert）。 */
  @Override
  public PidsBulletin save(PidsBulletin bulletin) {
    Objects.requireNonNull(bulletin, "bulletin");
    String update =
        "UPDATE "
            + table("pids_bulletins")
            + " SET company_id = ?, operator_code = ?, station_codes = ?, line_codes = ?,"
            + " level = ?, title = ?, title_secondary = ?, body = ?, body_secondary = ?,"
            + " starts_at = ?, ends_at = ?, created_by = ?, updated_at = ? WHERE id = ?";
    String insert =
        "INSERT INTO "
            + table("pids_bulletins")
            + " ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              int next = bindFields(statement, 1, bulletin);
              setInstant(statement, next, bulletin.updatedAt());
              setUuid(statement, next + 1, bulletin.id());
              if (statement.executeUpdate() > 0) {
                return bulletin;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, bulletin.id());
              int next = bindFields(statement, 2, bulletin);
              setInstant(statement, next, bulletin.createdAt());
              setInstant(statement, next + 1, bulletin.updatedAt());
              statement.executeUpdate();
            }
            return bulletin;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存站台屏公告失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    String sql = "DELETE FROM " + table("pids_bulletins") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除站台屏公告失败", ex);
    }
  }

  /** 绑定 {@code company_id} 到 {@code created_by} 共 12 列，返回下一个参数下标。 */
  private int bindFields(PreparedStatement statement, int start, PidsBulletin bulletin)
      throws SQLException {
    int i = start;
    setUuid(statement, i++, bulletin.companyId());
    statement.setString(i++, bulletin.operatorCode());
    statement.setString(i++, gson.toJson(List.copyOf(bulletin.stations())));
    statement.setString(i++, gson.toJson(List.copyOf(bulletin.lines())));
    statement.setString(i++, bulletin.level().name());
    statement.setString(i++, bulletin.title().primary());
    statement.setString(i++, bulletin.title().secondary());
    statement.setString(i++, bulletin.body().primary());
    statement.setString(i++, bulletin.body().secondary());
    setInstant(statement, i++, bulletin.startsAt().orElse(null));
    setInstant(statement, i++, bulletin.endsAt().orElse(null));
    setUuid(statement, i++, bulletin.createdBy().orElse(null));
    return i;
  }

  private PidsBulletin map(ResultSet rs) throws SQLException {
    return new PidsBulletin(
        requireUuid(rs, "id"),
        requireUuid(rs, "company_id"),
        rs.getString("operator_code"),
        strings(rs.getString("station_codes")),
        strings(rs.getString("line_codes")),
        PidsBulletin.Level.valueOf(rs.getString("level")),
        new PidsBulletin.Text(rs.getString("title"), rs.getString("title_secondary")),
        new PidsBulletin.Text(rs.getString("body"), rs.getString("body_secondary")),
        Optional.ofNullable(readNullableInstant(rs, "starts_at")),
        Optional.ofNullable(readNullableInstant(rs, "ends_at")),
        Optional.ofNullable(readUuid(rs, "created_by")),
        readInstant(rs, "created_at"),
        readInstant(rs, "updated_at"));
  }

  private Set<String> strings(String json) {
    if (json == null || json.isBlank()) {
      return Set.of();
    }
    List<String> values = gson.fromJson(json, STRING_LIST);
    return values == null ? Set.of() : Set.copyOf(values);
  }
}
