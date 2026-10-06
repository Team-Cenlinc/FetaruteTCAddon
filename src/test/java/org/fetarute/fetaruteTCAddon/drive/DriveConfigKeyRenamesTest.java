package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("drive.yml 改过名的键")
class DriveConfigKeyRenamesTest {

  private static List<String> lines(String text) {
    return text.lines().toList();
  }

  @Test
  @DisplayName("只改所属段下一层的旧键，值与注释保留；别的段同名键不动")
  void renamesKeysInTheirSectionOnly() {
    List<String> migrated =
        DriveConfigKeyRenames.migrate(
            lines(
                """
                breaker-held-trains: 1
                driver:
                  # 拥堵保护
                  breaker-held-trains: 9   # 列
                  breaker-held-seconds: 45
                  cab-change:
                    breaker-held-trains: 2
                sounds:
                  signal-confirmed:
                    volume: 0.3
                  "breaker-held-trains": x
                """));

    assertEquals(
        lines(
            """
            breaker-held-trains: 1
            driver:
              # 拥堵保护
              protection-held-trains: 9   # 列
              protection-held-seconds: 45
              cab-change:
                breaker-held-trains: 2
            sounds:
              signal-acknowledged:
                volume: 0.3
              "breaker-held-trains": x
            """),
        migrated);
  }

  @Test
  @DisplayName("新键已写时旧键保持原样，不改成重名；再迁移一次不变")
  void keepsOldKeyWhenNewOneExists() {
    List<String> both =
        lines(
            """
            driver:
              protection-held-trains: 6
              breaker-held-trains: 9
            """);
    assertEquals(both, DriveConfigKeyRenames.migrate(both));

    List<String> once =
        DriveConfigKeyRenames.migrate(lines("driver:\n  breaker-cooldown-minutes: 20\n"));
    assertEquals(List.of("driver:", "  protection-cooldown-minutes: 20"), once);
    assertEquals(once, DriveConfigKeyRenames.migrate(once));
  }
}
