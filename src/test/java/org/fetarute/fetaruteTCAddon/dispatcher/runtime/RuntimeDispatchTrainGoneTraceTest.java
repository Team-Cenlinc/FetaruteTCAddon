package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 受管列车车厢、编组消失时的调试证据：调用链、发起方、区块、最近玩家与出车时长的取值。 */
class RuntimeDispatchTrainGoneTraceTest {

  /** 插件销毁：事件派发之后是 TrainCarts 的移除链，发起方是插件自己的销毁调用。 */
  @Test
  void pluginDestroyNamesThePluginAsOrigin() {
    List<String> frames =
        List.of(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener#traceMemberGone",
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener#onMemberRemove",
            "org.bukkit.plugin.RegisteredListener#callEvent",
            "com.bergerkiller.bukkit.common.utils.CommonUtil#callEvent",
            "com.bergerkiller.bukkit.tc.events.MemberRemoveEvent#call",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#removeMember",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#remove",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#remove",
            "com.bergerkiller.bukkit.tc.controller.MinecartMember#onDie",
            "com.bergerkiller.bukkit.common.internal.hooks.EntityHook#onDie",
            "net.minecraft.world.entity.Entity#discard",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#destroy",
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle#destroy",
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService#destroyTrainByName");

    assertEquals(
        "MinecartGroup.removeMember<MinecartGroup.remove<MinecartMember.onDie<EntityHook.onDie"
            + "<Entity.discard<MinecartGroup.destroy",
        RuntimeDispatchListener.callerChain(frames));
    assertEquals("TrainCartsRuntimeHandle.destroy", RuntimeDispatchListener.callerOrigin(frames));
  }

  /** 区块卸载、实体随区块移除：派发之后全是服务端与 TrainCarts 内部，发起方记为 none。 */
  @Test
  void serverSideRemovalHasNoOrigin() {
    List<String> frames =
        List.of(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener#onGroupUnload",
            "com.bergerkiller.bukkit.tc.events.GroupUnloadEvent#call",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#unload",
            "com.bergerkiller.bukkit.tc.controller.global.ChunkUnloadHandler#onChunkUnload",
            "ca.spottedleaf.moonrise.patches.chunk_system.ChunkHolder#unload",
            "net.minecraft.server.level.ServerLevel#tick");

    assertEquals("none", RuntimeDispatchListener.callerOrigin(frames));
    assertEquals(
        "MinecartGroup.unload<ChunkUnloadHandler.onChunkUnload<ChunkHolder.unload<ServerLevel.tick",
        RuntimeDispatchListener.callerChain(frames));
  }

  /** TrainCarts 自己的命令、牌子与监听器不算内部实现，记为发起方。 */
  @Test
  void trainCartsCommandIsAnOrigin() {
    List<String> frames =
        List.of(
            "com.bergerkiller.bukkit.tc.events.GroupRemoveEvent#call",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#remove",
            "com.bergerkiller.bukkit.tc.controller.MinecartGroup#destroy",
            "com.bergerkiller.bukkit.tc.commands.TrainCommands#commandDestroy");

    assertEquals("TrainCommands.commandDestroy", RuntimeDispatchListener.callerOrigin(frames));
  }

  /** 调用链只列最近的几帧。 */
  @Test
  void callerChainIsBounded() {
    List<String> frames = new ArrayList<>();
    frames.add("com.bergerkiller.bukkit.tc.events.MemberRemoveEvent#call");
    for (int i = 0; i < 20; i++) {
      frames.add("a.b.C" + i + "#m");
    }

    assertEquals("C0.m<C1.m<C2.m<C3.m<C4.m<C5.m", RuntimeDispatchListener.callerChain(frames));
  }

  @Test
  void framesWithoutEventDispatchAreUnknown() {
    List<String> frames = List.of("a.b.C#m", "org.fetarute.X#y");

    assertEquals("-", RuntimeDispatchListener.callerChain(frames));
    assertEquals("-", RuntimeDispatchListener.callerOrigin(frames));
    assertEquals("-", RuntimeDispatchListener.callerChain(null));
  }

  @Test
  void nearestPlayerIsMeasuredInTheSameWorld() {
    World world = mock(World.class);
    Player near = player(new Location(world, 10, 64, 0));
    Player far = player(new Location(world, 100, 64, 0));
    when(world.getPlayers()).thenReturn(List.of(far, near));

    assertEquals("10", RuntimeDispatchListener.nearestPlayerText(new Location(world, 0, 64, 0)));
  }

  @Test
  void nearestPlayerWithoutPlayersOrLocation() {
    World world = mock(World.class);
    when(world.getPlayers()).thenReturn(List.of());

    assertEquals("none", RuntimeDispatchListener.nearestPlayerText(new Location(world, 0, 64, 0)));
    assertEquals("-", RuntimeDispatchListener.nearestPlayerText(null));
  }

  @Test
  void chunkLoadedIsReadAtTheBlockChunk() {
    World world = mock(World.class);
    when(world.isChunkLoaded(-1, 2)).thenReturn(true);

    assertEquals("true", RuntimeDispatchListener.chunkLoadedText(new Location(world, -3, 64, 40)));
    assertEquals("false", RuntimeDispatchListener.chunkLoadedText(new Location(world, 40, 64, 40)));
    assertEquals("-", RuntimeDispatchListener.chunkLoadedText(null));
  }

  @Test
  void runAgeComesFromTheRunStartTag() {
    AtomicLong now = new AtomicLong(1_500_000L);
    RuntimeDispatchListener listener =
        new RuntimeDispatchListener(
            mock(RuntimeDispatchService.class), Runnable::run, message -> {}, now::get);
    TrainProperties started = mock(TrainProperties.class);
    when(started.hasTags()).thenReturn(true);
    when(started.getTags()).thenReturn(List.of("FTA_RUN_AT=1000000"));
    TrainProperties untagged = mock(TrainProperties.class);

    assertEquals("500000", listener.runAgeText(started));
    assertEquals("-", listener.runAgeText(untagged));
  }

  private static Player player(Location location) {
    Player player = mock(Player.class);
    when(player.getLocation()).thenReturn(location);
    return player;
  }
}
