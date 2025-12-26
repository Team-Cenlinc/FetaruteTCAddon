package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FtaRootCommandTabCompleteTest {

  /** `/fta<TAB>` 应列出可用子命令，并按权限隐藏 reload。 */
  @Test
  void completesSubCommandsWhenNoArgs() {
    FtaRootCommand root = new FtaRootCommand(null, null, null);

    CommandSender sender = Mockito.mock(CommandSender.class);
    Mockito.when(sender.hasPermission("fetarute.reload")).thenReturn(false);
    Mockito.when(sender.hasPermission("fetarute.graph")).thenReturn(false);
    Mockito.when(sender.hasPermission("fetarute.admin")).thenReturn(false);

    assertEquals(
        java.util.List.of("info", "help", "company", "operator", "line", "route"),
        root.onTabComplete(sender, Mockito.mock(Command.class), "fta", new String[] {}));
  }

  /** `/fta <TAB>` 应同样列出可用子命令。 */
  @Test
  void completesSubCommandsWhenFirstArgEmpty() {
    FtaRootCommand root = new FtaRootCommand(null, null, null);

    CommandSender sender = Mockito.mock(CommandSender.class);
    Mockito.when(sender.hasPermission("fetarute.reload")).thenReturn(true);
    Mockito.when(sender.hasPermission("fetarute.graph")).thenReturn(true);
    Mockito.when(sender.hasPermission("fetarute.admin")).thenReturn(false);

    assertEquals(
        java.util.List.of(
            "info", "help", "company", "operator", "line", "route", "graph", "reload"),
        root.onTabComplete(sender, Mockito.mock(Command.class), "fta", new String[] {""}));
  }

  /** 子命令应支持前缀过滤。 */
  @Test
  void filtersByPrefix() {
    FtaRootCommand root = new FtaRootCommand(null, null, null);

    CommandSender sender = Mockito.mock(CommandSender.class);
    Mockito.when(sender.hasPermission("fetarute.reload")).thenReturn(true);
    Mockito.when(sender.hasPermission("fetarute.graph")).thenReturn(true);
    Mockito.when(sender.hasPermission("fetarute.admin")).thenReturn(false);

    assertEquals(
        java.util.List.of("reload"),
        root.onTabComplete(sender, Mockito.mock(Command.class), "fta", new String[] {"re"}));
  }
}
