package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.GraphStaleListener.Level;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class GraphStaleNotifierTest {

  private final FakeHost host = new FakeHost();
  private final RailGraphService graph = mock(RailGraphService.class);
  private final LocaleManager locale = mock(LocaleManager.class);
  private final LoggerManager logger = mock(LoggerManager.class);
  private World world;
  private Player admin;
  private Player visitor;
  private GraphStaleNotifier notifier;

  @BeforeEach
  void setUp() {
    // 语言组件渲染成 "键 {占位符}"，断言只看发了哪些键。
    when(locale.component(anyString()))
        .thenAnswer(invocation -> Component.text(invocation.<String>getArgument(0)));
    when(locale.component(anyString(), anyMap()))
        .thenAnswer(
            invocation ->
                Component.text(
                    invocation.<String>getArgument(0) + " " + invocation.getArgument(1)));
    when(locale.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("surc");
    host.worlds.add(world);
    admin = player(true);
    visitor = player(false);
    host.players.add(admin);
    host.players.add(visitor);
    notifier = new GraphStaleNotifier(host, graph, locale, logger);
  }

  @Test
  void sameTickChangesBecomeOneAdminAlert() {
    markStale(true);

    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);
    notifier.onStale(world, change("SURC:S:DPB:1", 5, 64, 2, true), Level.EVICTED, Level.EVICTED);
    assertEquals(1, host.nextTick.size(), "同一 tick 只排一次 flush");
    host.runNextTick();

    List<String> lines = messages(admin);
    assertEquals(4, lines.size(), lines.toString());
    assertTrue(lines.get(0).startsWith("graph.alert.stale"), lines.get(0));
    assertTrue(lines.get(0).contains("removed=2"), lines.get(0));
    assertTrue(lines.get(1).contains("SURC:S:DPB:2"), lines.get(1));
    assertTrue(lines.get(2).contains("SURC:S:DPB:1"), lines.get(2));
    assertEquals("graph.alert.hint", lines.get(3));
    verify(visitor, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
    verify(logger).warn(contains("-SURC:S:DPB:2@1,64,2"));
  }

  @Test
  void changesOnAlreadyStaleWorldOnlyLogToConsole() {
    markStale(true);

    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, false), Level.EVICTED, Level.EVICTED);
    host.runNextTick();

    verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
    verify(logger).info(contains("仍处于失效状态"));
    verify(logger, never()).warn(anyString());
  }

  @Test
  void longBatchListsFirstEntriesAndCountsTheRest() {
    markStale(true);

    int total = GraphStaleNotifier.MAX_LISTED_CHANGES + 3;
    for (int i = 0; i < total; i++) {
      notifier.onStale(world, change("SURC:W:" + i, i, 64, 0, true), Level.NONE, Level.EVICTED);
    }
    host.runNextTick();

    List<String> lines = messages(admin);
    assertEquals(1 + GraphStaleNotifier.MAX_LISTED_CHANGES + 2, lines.size(), lines.toString());
    assertTrue(lines.get(lines.size() - 2).startsWith("graph.alert.more"));
    assertTrue(lines.get(lines.size() - 2).contains("count=3"));
  }

  @Test
  void graphFixedBeforeFlushIsNotBroadcast() {
    markStale(false);

    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);
    host.runNextTick();

    verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
  }

  @Test
  void recoveryAfterAnnouncedFailureIsBroadcast() {
    markStale(true);
    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);
    host.runNextTick();
    markStale(false);
    notifier.onRecovered(world);

    List<String> lines = messages(admin);
    assertTrue(lines.get(lines.size() - 1).startsWith("graph.alert.recovered"), lines.toString());
  }

  @Test
  void recoveryOfWorldStaleSinceStartupIsBroadcast() {
    notifier.onRecovered(world);

    List<String> lines = messages(admin);
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.get(0).startsWith("graph.alert.recovered"), lines.toString());
  }

  @Test
  void newFailureRecoveredInSameTickStaysSilentAfterEarlierAnnouncement() {
    markStale(true);
    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);
    host.runNextTick();
    int announced = messages(admin).size();

    // 期间图被重建过：下一次失效又从"正常"开始，并在同一 tick 内恢复。
    notifier.onStale(world, change("SURC:S:DPB:1", 5, 64, 2, true), Level.NONE, Level.EVICTED);
    markStale(false);
    notifier.onRecovered(world);
    host.runNextTick();

    assertEquals(announced, messages(admin).size());
  }

  @Test
  void recoveryInSameTickCancelsPendingAlert() {
    markStale(true);
    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);
    markStale(false);
    notifier.onRecovered(world);
    host.runNextTick();

    verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
    verify(logger).info(contains("-SURC:S:DPB:2@1,64,2"));
  }

  @Test
  void rejectedSchedulerStillDeliversTheAlert() {
    markStale(true);
    host.rejectTasks = true;

    notifier.onStale(world, change("SURC:S:DPB:2", 1, 64, 2, true), Level.NONE, Level.EVICTED);

    List<String> lines = messages(admin);
    assertTrue(lines.get(0).startsWith("graph.alert.stale"), lines.toString());
    assertTrue(lines.get(1).contains("SURC:S:DPB:2"), lines.toString());
  }

  @Test
  void adminJoiningWhileStaleIsReminded() {
    markStale(true);
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(admin);

    notifier.onPlayerJoin(event);
    assertEquals(1, host.later.size());
    host.later.remove(0).run();

    List<String> lines = messages(admin);
    assertEquals(2, lines.size(), lines.toString());
    assertTrue(lines.get(0).startsWith("graph.alert.join"));
    assertEquals("graph.alert.hint", lines.get(1));
  }

  @Test
  void joinReminderIsSilentWhenNothingIsStaleOrPlayerLacksPermission() {
    PlayerJoinEvent visitorJoin = mock(PlayerJoinEvent.class);
    when(visitorJoin.getPlayer()).thenReturn(visitor);
    notifier.onPlayerJoin(visitorJoin);
    assertTrue(host.later.isEmpty());

    markStale(false);
    notifier.remind(admin);
    verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
  }

  @Test
  void startupReportsStaleWorldsToConsole() {
    markStale(true);

    notifier.logStaleWorlds();

    verify(logger).warn(contains("world=surc"));
  }

  @Test
  void unusedChangesAnnounceThatTheOldGraphStaysInUse() {
    markRetained();

    notifier.onStale(world, change("SURC:W:1", 1, 64, 2, true), Level.NONE, Level.RETAINED);
    host.runNextTick();

    List<String> lines = messages(admin);
    assertTrue(lines.get(0).startsWith("graph.alert.retained"), lines.toString());
    verify(logger, never()).warn(anyString());
  }

  @Test
  void escalationFromRetainedToEvictedIsBroadcastAsFailure() {
    markStale(true);

    notifier.onStale(
        world, used(change("SURC:S:DPB:2", 1, 64, 2, true)), Level.RETAINED, Level.EVICTED);
    host.runNextTick();

    List<String> lines = messages(admin);
    assertTrue(lines.get(0).startsWith("graph.alert.stale"), lines.toString());
    assertTrue(lines.get(1).startsWith("graph.alert.entry-used"), lines.get(1));
    assertTrue(lines.get(1).contains("交路 MT-3 第 5 站"), lines.get(1));
  }

  @Test
  void furtherUnusedChangesOnRetainedWorldOnlyLogToConsole() {
    markRetained();

    notifier.onStale(world, change("SURC:W:2", 1, 64, 2, false), Level.RETAINED, Level.RETAINED);
    host.runNextTick();

    verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
    verify(logger).info(contains("继续使用原图"));
  }

  @Test
  void adminJoiningWhileOldGraphIsRetainedGetsTheSofterReminder() {
    markRetained();

    notifier.remind(admin);

    List<String> lines = messages(admin);
    assertTrue(lines.get(0).startsWith("graph.alert.join-retained"), lines.toString());
  }

  private void markRetained() {
    when(graph.getStaleState(world))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphStaleState(Instant.EPOCH, "a", "b", 1, 0, 1, true)));
    when(graph.isServingRetainedStaleSnapshot(world.getUID())).thenReturn(true);
  }

  private static GraphStaleListener.NodeChange used(GraphStaleListener.NodeChange change) {
    return change.withUsage(Optional.of("交路 MT-3 第 5 站"));
  }

  private void markStale(boolean stale) {
    when(graph.getStaleState(world))
        .thenReturn(
            stale
                ? Optional.of(
                    new RailGraphService.RailGraphStaleState(Instant.EPOCH, "a", "b", 1, 0, 1))
                : Optional.empty());
  }

  private static GraphStaleListener.NodeChange change(
      String node, int x, int y, int z, boolean removed) {
    return new GraphStaleListener.NodeChange(
        new SignNodeDefinition(
            NodeId.of(node), NodeType.STATION, Optional.of(node), Optional.empty()),
        x,
        y,
        z,
        removed);
  }

  private static Player player(boolean alerts) {
    Player player = mock(Player.class);
    when(player.hasPermission(GraphStaleNotifier.ALERT_PERMISSION)).thenReturn(alerts);
    when(player.isOnline()).thenReturn(true);
    return player;
  }

  private static List<String> messages(Player player) {
    ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
    List<String> lines = new ArrayList<>();
    for (Component component : captor.getAllValues()) {
      lines.add(PlainTextComponentSerializer.plainText().serialize(component));
    }
    return lines;
  }

  private static final class FakeHost implements GraphStaleNotifier.Host {
    private final List<Player> players = new ArrayList<>();
    private final List<World> worlds = new ArrayList<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private boolean rejectTasks;

    @Override
    public Collection<? extends Player> onlinePlayers() {
      return players;
    }

    @Override
    public List<World> worlds() {
      return worlds;
    }

    @Override
    public void runNextTick(Runnable task) {
      if (rejectTasks) {
        throw new IllegalStateException("plugin disabled");
      }
      nextTick.add(task);
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
      later.add(task);
    }

    private void runNextTick() {
      List<Runnable> tasks = new ArrayList<>(nextTick);
      nextTick.clear();
      tasks.forEach(Runnable::run);
    }
  }
}
