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
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.repository.PidsScreenRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的站台屏仓库。站台与线路过滤各存为一个 JSON 字符串数组。 */
public final class JdbcPidsScreenRepository extends JdbcRepositorySupport
    implements PidsScreenRepository {

  private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

  private static final String COLUMNS =
      "id, world_id, x, y, z, facing, tile_rows, tile_cols, layout_id, operator_code,"
          + " station_code, platforms, line_codes, appearance, mode, created_at, updated_at";

  public JdbcPidsScreenRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<PidsScreen> findById(UUID id) {
    String sql = "SELECT " + COLUMNS + " FROM " + table("pids_screens") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询站台屏失败", ex);
    }
  }

  @Override
  public List<PidsScreen> listAll() {
    String sql = "SELECT " + COLUMNS + " FROM " + table("pids_screens") + " ORDER BY created_at";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<PidsScreen> screens = new ArrayList<>();
      while (rs.next()) {
        screens.add(map(rs));
      }
      return screens;
    } catch (SQLException ex) {
      throw new StorageException("列出站台屏失败", ex);
    }
  }

  /** 先按 ID 更新，没有这一行再插入。不用方言 upsert：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 遇到位置唯一键冲突时会悄悄改写另一块屏幕。 */
  @Override
  public PidsScreen save(PidsScreen screen) {
    Objects.requireNonNull(screen, "screen");
    String update =
        "UPDATE "
            + table("pids_screens")
            + " SET world_id = ?, x = ?, y = ?, z = ?, facing = ?, tile_rows = ?, tile_cols = ?,"
            + " layout_id = ?, operator_code = ?, station_code = ?, platforms = ?, line_codes = ?,"
            + " appearance = ?, mode = ?, updated_at = ? WHERE id = ?";
    String insert =
        "INSERT INTO "
            + table("pids_screens")
            + " ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              int next = bindFields(statement, 1, screen);
              setInstant(statement, next, screen.updatedAt());
              setUuid(statement, next + 1, screen.id());
              if (statement.executeUpdate() > 0) {
                return screen;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, screen.id());
              int next = bindFields(statement, 2, screen);
              setInstant(statement, next, screen.createdAt());
              setInstant(statement, next + 1, screen.updatedAt());
              statement.executeUpdate();
            }
            return screen;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存站台屏失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    String sql = "DELETE FROM " + table("pids_screens") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除站台屏失败", ex);
    }
  }

  /** 绑定 {@code world_id} 到 {@code mode} 共 14 列，返回下一个参数下标。 */
  private int bindFields(PreparedStatement statement, int start, PidsScreen screen)
      throws SQLException {
    int i = start;
    setUuid(statement, i++, screen.worldId());
    statement.setInt(i++, screen.anchor().x());
    statement.setInt(i++, screen.anchor().y());
    statement.setInt(i++, screen.anchor().z());
    statement.setString(i++, screen.facing().name());
    statement.setInt(i++, screen.tileRows());
    statement.setInt(i++, screen.tileCols());
    statement.setString(i++, screen.layoutId());
    // 只绑运营商的屏幕（线路运行状况屏）：operator_code 有值、station_code 为空。
    statement.setString(i++, screen.operatorCode().orElse(null));
    statement.setString(i++, screen.station().map(PidsStationKey::stationCode).orElse(null));
    statement.setString(i++, gson.toJson(List.copyOf(screen.platforms())));
    statement.setString(i++, gson.toJson(List.copyOf(screen.lines())));
    statement.setString(i++, screen.appearance().name());
    statement.setString(i++, screen.mode().name());
    return i;
  }

  private PidsScreen map(ResultSet rs) throws SQLException {
    String operator = rs.getString("operator_code");
    String station = rs.getString("station_code");
    Optional<PidsStationKey> key =
        operator == null || station == null
            ? Optional.empty()
            : Optional.of(new PidsStationKey(operator, station));
    return new PidsScreen(
        requireUuid(rs, "id"),
        requireUuid(rs, "world_id"),
        new PidsScreen.Position(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
        PidsFacing.valueOf(rs.getString("facing")),
        rs.getInt("tile_rows"),
        rs.getInt("tile_cols"),
        rs.getString("layout_id"),
        key,
        key.isEmpty() && operator != null && !operator.isBlank()
            ? Optional.of(operator)
            : Optional.empty(),
        strings(rs.getString("platforms")),
        strings(rs.getString("line_codes")),
        PidsScreen.Appearance.valueOf(rs.getString("appearance")),
        PidsScreen.Mode.valueOf(rs.getString("mode")),
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
