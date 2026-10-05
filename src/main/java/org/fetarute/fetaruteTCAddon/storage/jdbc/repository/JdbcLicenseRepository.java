package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseRecord;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的驾驶证仓库。 */
public final class JdbcLicenseRepository extends JdbcRepositorySupport
    implements LicenseRepository {

  private static final String LICENSES = "drive_licenses";

  public JdbcLicenseRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<LicenseRecord> listByPlayer(UUID playerId) {
    Objects.requireNonNull(playerId, "playerId");
    String sql =
        "SELECT player_uuid, player_name, class_id, granted_at, granted_by FROM "
            + table(LICENSES)
            + " WHERE player_uuid = ? ORDER BY granted_at";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, playerId);
      List<LicenseRecord> records = new ArrayList<>();
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          records.add(
              new LicenseRecord(
                  requireUuid(rs, "player_uuid"),
                  rs.getString("player_name"),
                  rs.getString("class_id"),
                  readInstant(rs, "granted_at"),
                  rs.getString("granted_by")));
        }
      }
      return records;
    } catch (SQLException ex) {
      throw new StorageException("读取 drive_licenses 失败", ex);
    }
  }

  @Override
  public void grant(LicenseRecord record) {
    Objects.requireNonNull(record, "record");
    String insert =
        "INSERT INTO "
            + table(LICENSES)
            + " (player_uuid, player_name, class_id, granted_at, granted_by) VALUES (?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("player_uuid", "class_id"),
            List.of("player_name", "granted_at", "granted_by"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, record.playerId());
      statement.setString(2, record.playerName());
      statement.setString(3, record.classId());
      setInstant(statement, 4, record.grantedAt());
      statement.setString(5, record.grantedBy());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("保存 drive_licenses 失败", ex);
    }
  }

  @Override
  public boolean revoke(UUID playerId, String classId) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(classId, "classId");
    String sql = "DELETE FROM " + table(LICENSES) + " WHERE player_uuid = ? AND class_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, playerId);
      statement.setString(2, classId);
      int removed = statement.executeUpdate();
      connection.commitIfNecessary();
      return removed > 0;
    } catch (SQLException ex) {
      throw new StorageException("吊销 drive_licenses 失败", ex);
    }
  }
}
