package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveLeaderboardRow;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecordRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的驾驶任务记录仓库。 */
public final class JdbcDriveTaskRecordRepository extends JdbcRepositorySupport
    implements DriveTaskRecordRepository {

  private static final String TABLE = "drive_task_records";
  private static final String COMPLETED = "COMPLETED";

  public JdbcDriveTaskRecordRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public void save(DriveTaskRecord record) {
    Objects.requireNonNull(record, "record");
    String sql =
        "INSERT INTO "
            + table(TABLE)
            + " (id, server_id, player_uuid, player_name, timetable_id, trip_code, service_date,"
            + " route_code, train_name, mode, state, points, grade, started_at, finished_at,"
            + " detail_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, record.id());
      statement.setString(2, record.serverId());
      setUuid(statement, 3, record.playerId());
      statement.setString(4, record.playerName());
      setUuid(statement, 5, record.timetableId());
      statement.setString(6, record.tripCode());
      statement.setString(7, record.serviceDate().toString());
      statement.setString(8, record.routeCode());
      statement.setString(9, record.trainName());
      statement.setString(10, record.mode());
      statement.setString(11, record.state());
      statement.setInt(12, record.points());
      statement.setString(13, record.grade());
      setInstant(statement, 14, record.startedAt());
      setInstant(statement, 15, record.finishedAt());
      statement.setString(16, record.detailJson());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("保存 drive_task_records 失败", ex);
    }
  }

  @Override
  public List<DriveTaskRecord> listByPlayer(UUID playerId, int limit) {
    Objects.requireNonNull(playerId, "playerId");
    String sql =
        "SELECT * FROM "
            + table(TABLE)
            + " WHERE player_uuid = ? ORDER BY finished_at DESC LIMIT "
            + Math.max(1, limit);
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, playerId);
      try (ResultSet rs = statement.executeQuery()) {
        List<DriveTaskRecord> records = new ArrayList<>();
        while (rs.next()) {
          records.add(read(rs));
        }
        return records;
      }
    } catch (SQLException ex) {
      throw new StorageException("读取 drive_task_records 失败", ex);
    }
  }

  @Override
  public List<DriveLeaderboardRow> leaderboard(Instant since, int limit) {
    String sql =
        "SELECT player_uuid, MAX(player_name) AS player_name, COUNT(*) AS tasks,"
            + " SUM(points) AS total FROM "
            + table(TABLE)
            + " WHERE state = ?"
            + (since == null ? "" : " AND finished_at >= ?")
            + " GROUP BY player_uuid ORDER BY total DESC, tasks DESC LIMIT "
            + Math.max(1, limit);
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, COMPLETED);
      if (since != null) {
        setInstant(statement, 2, since);
      }
      List<DriveLeaderboardRow> rows = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new DriveLeaderboardRow(
                  requireUuid(rs, "player_uuid"),
                  rs.getString("player_name"),
                  rs.getInt("tasks"),
                  rs.getLong("total")));
        }
      }
      // 名字取最近一条记录里的（改名后显示新名字），聚合里的 MAX 只是兜底。
      String latest =
          "SELECT player_name FROM "
              + table(TABLE)
              + " WHERE player_uuid = ? ORDER BY finished_at DESC LIMIT 1";
      List<DriveLeaderboardRow> named = new ArrayList<>(rows.size());
      try (var nameStatement = connection.prepareStatement(latest)) {
        for (DriveLeaderboardRow row : rows) {
          setUuid(nameStatement, 1, row.playerId());
          String name = row.playerName();
          try (ResultSet rs = nameStatement.executeQuery()) {
            if (rs.next() && rs.getString("player_name") != null) {
              name = rs.getString("player_name");
            }
          }
          named.add(new DriveLeaderboardRow(row.playerId(), name, row.tasks(), row.totalPoints()));
        }
      }
      return named;
    } catch (SQLException ex) {
      throw new StorageException("读取驾驶排行失败", ex);
    }
  }

  /** 评级从好到差；汇总时按下标取最小。 */
  private static final String GRADES = "SABCD";

  @Override
  public PlayerTotals totalsByPlayer(UUID playerId) {
    Objects.requireNonNull(playerId, "playerId");
    String sql =
        "SELECT COUNT(*) AS tasks,"
            + " SUM(CASE WHEN state = ? THEN 1 ELSE 0 END) AS completed,"
            + " SUM(CASE WHEN state = ? AND points > 0 THEN points ELSE 0 END) AS completed_points,"
            + " MIN(CASE grade WHEN 'S' THEN 0 WHEN 'A' THEN 1 WHEN 'B' THEN 2 WHEN 'C' THEN 3"
            + " WHEN 'D' THEN 4 END) AS best_rank FROM "
            + table(TABLE)
            + " WHERE player_uuid = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, COMPLETED);
      statement.setString(2, COMPLETED);
      setUuid(statement, 3, playerId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return new PlayerTotals(0, 0, 0L, "");
        }
        int tasks = rs.getInt("tasks");
        int completed = rs.getInt("completed");
        long points = rs.getLong("completed_points");
        int rank = rs.getInt("best_rank");
        String best = rs.wasNull() ? "" : String.valueOf(GRADES.charAt(rank));
        return new PlayerTotals(tasks, completed, points, best);
      }
    } catch (SQLException ex) {
      throw new StorageException("汇总 drive_task_records 失败", ex);
    }
  }

  private DriveTaskRecord read(ResultSet rs) throws SQLException {
    return new DriveTaskRecord(
        requireUuid(rs, "id"),
        rs.getString("server_id"),
        requireUuid(rs, "player_uuid"),
        rs.getString("player_name"),
        requireUuid(rs, "timetable_id"),
        rs.getString("trip_code"),
        LocalDate.parse(rs.getString("service_date")),
        rs.getString("route_code"),
        rs.getString("train_name"),
        rs.getString("mode"),
        rs.getString("state"),
        rs.getInt("points"),
        rs.getString("grade"),
        readInstant(rs, "started_at"),
        readInstant(rs, "finished_at"),
        rs.getString("detail_json"));
  }
}
