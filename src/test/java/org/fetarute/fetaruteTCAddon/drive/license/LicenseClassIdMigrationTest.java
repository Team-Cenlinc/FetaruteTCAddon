package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶证等级改名：drive.yml 迁移")
class LicenseClassIdMigrationTest {

  private static final String OLD =
      """
      enabled: true
      license:
        enabled: true
        training:
          routes: []
        # 各级驾驶证
        classes:
          free:
            name: "自由驾驶证"
            requires: []
            exam: tutorial
          dispatch:
            name: "调度驾驶证"   # 第 2 级
            requires:
              - free
            exam: dispatch
            min-points: 80
          extra:
            name: 自定义
            requires: [free, "dispatch"]
            exam: dispatch
      sounds:
        free:
          name: "自由驾驶证"
      """;

  private static List<String> lines(String text) {
    return text.lines().toList();
  }

  @Test
  @DisplayName("改等级键、requires 引用与旧默认名称；考试方式、其他段与改过的值不动")
  void renamesClassesInsideLicenseClassesOnly() {
    List<String> migrated = LicenseClassIdMigration.migrate(lines(OLD));

    assertEquals(
        lines(
            """
            enabled: true
            license:
              enabled: true
              training:
                routes: []
              # 各级驾驶证
              classes:
                learner:
                  name: "见习驾驶证"
                  requires: []
                  exam: tutorial
                driver:
                  name: "正式驾驶证"   # 第 2 级
                  requires:
                    - learner
                  exam: dispatch
                  min-points: 80
                extra:
                  name: 自定义
                  requires: [learner, "driver"]
                  exam: dispatch
            sounds:
              free:
                name: "自由驾驶证"
            """),
        migrated);
  }

  @Test
  @DisplayName("已迁移过的文件不再改；新键已存在时不把旧键改成重名")
  void isIdempotentAndAvoidsDuplicateKeys() {
    List<String> once = LicenseClassIdMigration.migrate(lines(OLD));
    assertEquals(once, LicenseClassIdMigration.migrate(once));

    List<String> both =
        lines(
            """
            license:
              classes:
                learner:
                  name: "见习驾驶证"
                free:
                  name: 我的自由证
            """);
    List<String> migrated = LicenseClassIdMigration.migrate(both);
    assertEquals("    free:", migrated.get(4), "learner 已存在时 free 保持原名");
    assertEquals("      name: 我的自由证", migrated.get(5), "用户改过的名称不动");
  }

  @Test
  @DisplayName("数据库改名：旧 ID 改成新 ID，同一玩家已有新 ID 时跳过那一行")
  void renamesDatabaseRowsWithoutPrimaryKeyConflicts() throws Exception {
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE t (player_uuid TEXT NOT NULL, class_id TEXT NOT NULL,"
              + " PRIMARY KEY (player_uuid, class_id))");
      statement.execute(
          "INSERT INTO t VALUES ('a', 'free'), ('a', 'dispatch'), ('b', 'free'), ('b', 'learner')");
      for (var rename : List.of(List.of("learner", "free"), List.of("driver", "dispatch"))) {
        try (java.sql.PreparedStatement update =
            connection.prepareStatement(LicenseClassIdMigration.renameSql("t"))) {
          update.setString(1, rename.get(0));
          update.setString(2, rename.get(1));
          update.setString(3, rename.get(0));
          update.executeUpdate();
        }
      }
      java.util.List<String> rows = new java.util.ArrayList<>();
      try (java.sql.ResultSet result =
          statement.executeQuery("SELECT player_uuid || ':' || class_id FROM t ORDER BY 1")) {
        while (result.next()) {
          rows.add(result.getString(1));
        }
      }
      assertEquals(List.of("a:driver", "a:learner", "b:free", "b:learner"), rows);
    }
  }
}
