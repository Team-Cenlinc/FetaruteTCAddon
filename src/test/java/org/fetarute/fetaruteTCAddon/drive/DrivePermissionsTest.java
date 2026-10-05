package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("手动驾驶的权限节点")
class DrivePermissionsTest {

  private static YamlConfiguration resource(String path) throws Exception {
    return resource(path, '.');
  }

  /** plugin.yml 的节点名本身带点号：换个路径分隔符，节点名才不会被拆成嵌套的路径。 */
  private static YamlConfiguration pluginYml() throws Exception {
    return resource("plugin.yml", '/');
  }

  private static YamlConfiguration resource(String path, char separator) throws Exception {
    try (InputStream stream =
        DrivePermissionsTest.class.getClassLoader().getResourceAsStream(path)) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.options().pathSeparator(separator);
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  private static ConfigurationSection node(YamlConfiguration plugin, String node) {
    return plugin.getConfigurationSection("permissions/" + node);
  }

  /** 按 plugin.yml 的 children 展开：授予这些节点后实际持有的全部节点。 */
  private static Set<String> expand(YamlConfiguration plugin, String... granted) {
    Set<String> held = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(granted));
    while (!queue.isEmpty()) {
      String name = queue.pop();
      if (!held.add(name)) {
        continue;
      }
      ConfigurationSection children =
          plugin.getConfigurationSection("permissions/" + name + "/children");
      if (children == null) {
        continue;
      }
      for (String child : children.getKeys(false)) {
        if (children.getBoolean(child)) {
          queue.push(child);
        }
      }
    }
    return held;
  }

  @Test
  @DisplayName("已部署服务器在用的节点名保持不变")
  void existingNodeNamesAreKept() {
    assertEquals("fetarute.drive", DrivePermissions.BASE);
    assertEquals("fetarute.drive.admin", DrivePermissions.ADMIN);
    assertEquals("fetarute.drive.driver", DrivePermissions.DRIVER);
    assertEquals("fetarute.drive.driver.admin", DrivePermissions.DRIVER_ADMIN);
  }

  @Test
  @DisplayName("每个子命令都有节点；玩家功能与管理命令分开")
  void subcommandMapping() {
    assertEquals(List.of(DrivePermissions.BASE), DrivePermissions.of("on"));
    assertEquals(List.of(DrivePermissions.BASE), DrivePermissions.of("reverser"));
    assertEquals(List.of(DrivePermissions.DRIVER), DrivePermissions.of("tasks"));
    assertEquals(List.of(DrivePermissions.DRIVER), DrivePermissions.of("task"));
    assertEquals(List.of(DrivePermissions.DRIVER), DrivePermissions.of("mode"));
    assertEquals(
        List.of(DrivePermissions.RECORDS, DrivePermissions.ADMIN), DrivePermissions.of("records"));
    assertEquals(List.of(DrivePermissions.TOP), DrivePermissions.of("top"));
    assertEquals(List.of(DrivePermissions.TUTORIAL), DrivePermissions.of("tutorial"));
    for (String admin : List.of("list", "stop", "revoke", "handback", "breaker", "probe")) {
      assertEquals(List.of(DrivePermissions.ADMIN), DrivePermissions.of(admin), admin);
    }
    assertThrows(IllegalArgumentException.class, () -> DrivePermissions.of("unknown"));
    assertTrue(
        DrivePermissions.anyCommand()
            .containsAll(
                List.of(
                    DrivePermissions.BASE,
                    DrivePermissions.DRIVER,
                    DrivePermissions.RECORDS,
                    DrivePermissions.TOP,
                    DrivePermissions.TUTORIAL,
                    DrivePermissions.ADMIN)));
  }

  @Test
  @DisplayName("帮助只列有权限的子命令")
  void helpIsFilteredByPermission() {
    assertEquals(
        List.of("on", "off", "status", "reverser"),
        DrivePermissions.visibleSubcommands(Set.of(DrivePermissions.BASE)::contains));
    assertEquals(
        List.of("records"),
        DrivePermissions.visibleSubcommands(Set.of(DrivePermissions.RECORDS)::contains));
    assertTrue(DrivePermissions.visibleSubcommands(Set.<String>of()::contains).isEmpty());
    List<String> admin =
        DrivePermissions.visibleSubcommands(Set.of(DrivePermissions.ADMIN)::contains);
    assertTrue(admin.containsAll(List.of("records", "revoke", "list", "stop", "probe")));
    assertFalse(admin.contains("on"));
  }

  @Test
  @DisplayName("驾驶非调度列车要自由驾驶节点，调度列车要驾驶员节点；ATO 另要 ATO 节点")
  void startAndModePermissions() {
    assertEquals(DrivePermissions.FREE, DrivePermissions.startPermission(false));
    assertEquals(DrivePermissions.DRIVER, DrivePermissions.startPermission(true));
    Set<String> manualOnly = Set.of(DrivePermissions.DRIVER);
    assertTrue(DrivePermissions.allowsMode(DrivingMode.MANUAL, manualOnly::contains));
    assertFalse(DrivePermissions.allowsMode(DrivingMode.ATO, manualOnly::contains));
    Set<String> withAto = Set.of(DrivePermissions.DRIVER, DrivePermissions.ATO);
    assertTrue(DrivePermissions.allowsMode(DrivingMode.ATO, withAto::contains));
    assertFalse(
        DrivePermissions.allowsMode(DrivingMode.ATO, Set.of(DrivePermissions.ATO)::contains),
        "ATO 节点不能代替驾驶员节点");
  }

  @Test
  @DisplayName("plugin.yml：每个节点都有说明，默认只给 OP")
  void pluginYmlDeclaresEveryNode() throws Exception {
    YamlConfiguration plugin = pluginYml();
    List<String> nodes =
        List.of(
            DrivePermissions.BASE,
            DrivePermissions.FREE,
            DrivePermissions.DRIVER,
            DrivePermissions.ATO,
            DrivePermissions.LEVEL,
            DrivePermissions.RECORDS,
            DrivePermissions.TOP,
            DrivePermissions.TUTORIAL,
            DrivePermissions.DRIVER_ADMIN,
            DrivePermissions.ADMIN,
            DrivePermissions.PLAYER);
    for (String name : nodes) {
      ConfigurationSection section = node(plugin, name);
      assertTrue(section != null, name + " 未在 plugin.yml 声明");
      assertFalse(section.getString("description", "").isBlank(), name + " 缺少说明");
      assertEquals("op", section.getString("default"), name + " 默认值应为 op");
    }
  }

  @Test
  @DisplayName("plugin.yml：打包节点含全部玩家功能，旧节点的授予效果不变")
  void pluginYmlChildren() throws Exception {
    YamlConfiguration plugin = pluginYml();
    Set<String> bundle = expand(plugin, DrivePermissions.PLAYER);
    assertTrue(bundle.containsAll(DrivePermissions.PLAYER_NODES), bundle.toString());
    assertFalse(bundle.contains(DrivePermissions.ADMIN), "打包节点不含管理命令");
    assertFalse(bundle.contains(DrivePermissions.DRIVER_ADMIN));

    Set<String> base = expand(plugin, DrivePermissions.BASE);
    assertTrue(base.contains(DrivePermissions.FREE), "只授予 fetarute.drive 的服务器仍可驾驶非调度列车");
    assertTrue(base.contains(DrivePermissions.TUTORIAL));
    assertFalse(base.contains(DrivePermissions.DRIVER));

    Set<String> driver = expand(plugin, DrivePermissions.DRIVER);
    assertTrue(
        driver.containsAll(
            List.of(
                DrivePermissions.BASE,
                DrivePermissions.FREE,
                DrivePermissions.ATO,
                DrivePermissions.LEVEL,
                DrivePermissions.RECORDS,
                DrivePermissions.TOP)),
        "只授予 fetarute.drive.driver 的服务器仍有原先的全部玩家功能: " + driver);

    Set<String> admin = expand(plugin, "fetarute.admin");
    assertTrue(admin.containsAll(DrivePermissions.PLAYER_NODES));
    assertTrue(admin.contains(DrivePermissions.ADMIN));
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("每个子命令都有帮助条目")
  void everySubcommandHasHelp(String localeTag) throws Exception {
    YamlConfiguration lang = resource("lang/" + localeTag + ".yml");
    for (DrivePermissions.Subcommand subcommand : DrivePermissions.SUBCOMMANDS) {
      String key = "drive.command.help.entry-" + subcommand.name();
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
