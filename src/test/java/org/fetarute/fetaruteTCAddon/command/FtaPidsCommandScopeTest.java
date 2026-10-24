package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 信号员控制台的命令在 {@code /fta signal console} 下、只有控制台用得上的设置；站台屏的命令留在 {@code /fta pids}。 */
class FtaPidsCommandScopeTest {

  /** 只有站台屏才有的子命令（前缀之后的部分）。 */
  private static final List<String> STATION_ONLY =
      List.of(
          "stick",
          "set screen station",
          "set screen platform",
          "set screen line",
          "set screen rotate",
          "set screen page");

  @Test
  void consoleCommandsLiveUnderSignalConsoleWithOnlyConsoleSettings() {
    Set<String> paths = paths(FtaPidsCommand.Scope.CONSOLE);

    assertTrue(
        paths.stream().allMatch(path -> path.startsWith("fta signal console")), () -> "" + paths);
    for (String expected :
        List.of(
            "fta signal console",
            "fta signal console give layout",
            "fta signal console list page",
            "fta signal console info screen",
            "fta signal console menu screen",
            "fta signal console set screen operator operator",
            "fta signal console set screen zone value",
            "fta signal console set screen layout value",
            "fta signal console set screen appearance value",
            "fta signal console set screen mode value",
            "fta signal console remove screen",
            "fta signal console remove screen confirm")) {
      assertTrue(paths.contains(expected), () -> expected + " 不在 " + paths);
    }
    for (String sub : STATION_ONLY) {
      assertTrue(
          paths.stream().noneMatch(path -> path.startsWith("fta signal console " + sub)),
          () -> "控制台不该有 " + sub + ": " + paths);
    }
  }

  @Test
  void pidsCommandsKeepTheStationSettingsAndStayUnderPids() {
    Set<String> paths = paths(FtaPidsCommand.Scope.PIDS);

    assertTrue(paths.stream().allMatch(path -> path.startsWith("fta pids")), () -> "" + paths);
    for (String sub : STATION_ONLY) {
      assertTrue(
          paths.stream().anyMatch(path -> path.startsWith("fta pids " + sub)),
          () -> "站台屏应有 " + sub + ": " + paths);
    }
    assertEquals(
        paths(FtaPidsCommand.Scope.CONSOLE).size() + 6,
        paths.size(),
        "站台屏比控制台多 stick、station、platform、line、rotate、page 六条");
  }

  private static Set<String> paths(FtaPidsCommand.Scope scope) {
    CommandManager<CommandSender> manager =
        new CommandManager<>(
            ExecutionCoordinator.simpleCoordinator(),
            CommandRegistrationHandler.nullCommandRegistrationHandler()) {
          @Override
          public boolean hasPermission(CommandSender sender, String permission) {
            return true;
          }
        };
    new FtaPidsCommand(Mockito.mock(FetaruteTCAddon.class), scope).register(manager);
    return manager.commands().stream()
        .map(
            command ->
                command.components().stream()
                    .map(CommandComponent::name)
                    .collect(Collectors.joining(" ")))
        .collect(Collectors.toSet());
  }
}
