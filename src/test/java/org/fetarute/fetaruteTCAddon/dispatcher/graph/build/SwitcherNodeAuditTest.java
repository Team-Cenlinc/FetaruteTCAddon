package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作废道岔节点核对：找一点放一点")
class SwitcherNodeAuditTest {

  private static final UUID WORLD = UUID.randomUUID();
  private static final long FAR_FUTURE = Long.MAX_VALUE;

  /** 假区块：记录加载、挂票与撤票；加载由测试手动完成，撤票按服务器规则决定是否卸载。 */
  private static final class FakeChunks implements SwitcherNodeAudit.ChunkAccess {
    final Set<Long> loaded = new HashSet<>();
    final Map<Long, CompletableFuture<Void>> pending = new HashMap<>();
    final Set<Long> tickets = new HashSet<>();
    final Set<Long> foreignTickets = new HashSet<>();
    final List<Long> loadRequests = new ArrayList<>();

    static long key(int cx, int cz) {
      return SwitcherNodeAudit.chunkKey(cx, cz);
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
      return loaded.contains(key(cx, cz));
    }

    @Override
    public CompletableFuture<?> load(int cx, int cz) {
      long key = key(cx, cz);
      loadRequests.add(key);
      CompletableFuture<Void> future = new CompletableFuture<>();
      pending.put(key, future);
      return future;
    }

    @Override
    public boolean hold(int cx, int cz) {
      return isLoaded(cx, cz) && tickets.add(key(cx, cz));
    }

    @Override
    public void release(int cx, int cz) {
      long key = key(cx, cz);
      tickets.remove(key);
      if (!foreignTickets.contains(key)) {
        loaded.remove(key);
      }
    }

    int inFlight() {
      return (int) pending.values().stream().filter(future -> !future.isDone()).count();
    }

    void completeAll() {
      pending.forEach(
          (key, future) -> {
            if (!future.isDone()) {
              loaded.add(key);
              future.complete(null);
            }
          });
    }

    void fail(int cx, int cz) {
      pending.get(key(cx, cz)).completeExceptionally(new IllegalStateException("io"));
    }
  }

  private static RailNodeRecord switcher(int x, int y, int z) {
    return new RailNodeRecord(
        WORLD,
        NodeId.of("SWITCHER:world:" + x + ":" + y + ":" + z),
        NodeType.SWITCHER,
        x,
        y,
        z,
        Optional.empty(),
        Optional.empty());
  }

  private static SwitcherNodeAudit.Result runToEnd(SwitcherNodeAudit audit, FakeChunks chunks) {
    for (int i = 0; i < 1000 && !audit.step(FAR_FUTURE); i++) {
      chunks.completeAll();
    }
    assertTrue(audit.isDone(), "核对应当走完");
    return audit.result();
  }

  @Test
  @DisplayName("区块都已加载时当场判定，作废的列出、有效的保留，用完的票全部撤掉")
  void judgesLoadedSwitchersAndReleasesItsTickets() {
    FakeChunks chunks = new FakeChunks();
    for (int cx = -1; cx <= 1; cx++) {
      for (int cz = -1; cz <= 1; cz++) {
        chunks.loaded.add(FakeChunks.key(cx, cz));
      }
    }
    RailNodeRecord stale = switcher(5, 64, 5);
    RailNodeRecord valid = switcher(8, 64, 8);
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(List.of(stale, valid), chunks, rail -> rail.x() == 5, 4, 256);

    SwitcherNodeAudit.Result result = runToEnd(audit, chunks);

    assertEquals(List.of(stale), result.stale());
    assertTrue(result.unverified().isEmpty());
    assertTrue(chunks.loadRequests.isEmpty(), "已加载的区块不该再加载");
    assertTrue(chunks.tickets.isEmpty(), "核对结束后不留票");
  }

  @Test
  @DisplayName("在区块边上的道岔要把相邻区块一起加载，判定前一个都不能少")
  void loadsTheNeighbourChunksOfASwitcherOnAChunkEdge() {
    FakeChunks chunks = new FakeChunks();
    // 玩家所在的区块：本来就加载着，有玩家的票
    chunks.loaded.add(FakeChunks.key(0, 0));
    chunks.foreignTickets.add(FakeChunks.key(0, 0));
    List<RailBlockPos> judged = new ArrayList<>();
    // x=15、z=0：x+1 落到 cx=1，z-1 落到 cz=-1
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(
            List.of(switcher(15, 64, 0)),
            chunks,
            rail -> {
              for (int cx = 0; cx <= 1; cx++) {
                for (int cz = -1; cz <= 0; cz++) {
                  assertTrue(chunks.isLoaded(cx, cz), "判定时相邻区块必须已加载");
                }
              }
              judged.add(rail);
              return false;
            },
            4,
            256);

    assertFalse(audit.step(FAR_FUTURE), "相邻区块未到位前不判定");
    assertTrue(judged.isEmpty());
    assertEquals(3, chunks.loadRequests.size());

    SwitcherNodeAudit.Result result = runToEnd(audit, chunks);

    assertEquals(1, judged.size());
    assertTrue(result.stale().isEmpty());
    assertTrue(chunks.tickets.isEmpty());
    assertEquals(Set.of(FakeChunks.key(0, 0)), chunks.loaded, "为核对加载的区块撤票后交还服务器卸载");
  }

  @Test
  @DisplayName("同一时间为核对加载的区块不超过窗口；判完一批就放，再推进下一批")
  void keepsAtMostTheWindowOfChunksLoadedForTheAudit() {
    FakeChunks chunks = new FakeChunks();
    List<RailNodeRecord> nodes = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      // 每个道岔在区块正中，只需要它自己那一个区块
      nodes.add(switcher(i * 64 + 8, 64, 8));
    }
    SwitcherNodeAudit audit = new SwitcherNodeAudit(nodes, chunks, rail -> false, 2, 256);

    int peak = 0;
    for (int i = 0; i < 100 && !audit.step(FAR_FUTURE); i++) {
      peak = Math.max(peak, chunks.tickets.size() + chunks.inFlight());
      chunks.completeAll();
      peak = Math.max(peak, chunks.tickets.size() + chunks.inFlight());
    }

    assertTrue(audit.isDone());
    assertTrue(peak <= 2, "窗口 2 时同时占用的区块不超过 2：" + peak);
    assertEquals(6, chunks.loadRequests.size());
    assertTrue(chunks.tickets.isEmpty());
    assertTrue(chunks.loaded.isEmpty());
  }

  @Test
  @DisplayName("撤的只是自己的票：区块上还有别人的票时照样留在内存里")
  void releasingOurTicketKeepsAChunkThatSomeoneElseHolds() {
    FakeChunks chunks = new FakeChunks();
    chunks.loaded.add(FakeChunks.key(0, 0));
    chunks.foreignTickets.add(FakeChunks.key(0, 0));
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(List.of(switcher(8, 64, 8)), chunks, rail -> false, 4, 256);

    runToEnd(audit, chunks);

    assertTrue(chunks.tickets.isEmpty());
    assertTrue(chunks.isLoaded(0, 0), "别人还要的区块不能被卸载");
  }

  @Test
  @DisplayName("超出加载总量的道岔不判定，保留并记为未核对")
  void keepsSwitchersBeyondTheChunkBudgetAsUnverified() {
    FakeChunks chunks = new FakeChunks();
    RailNodeRecord first = switcher(8, 64, 8);
    RailNodeRecord second = switcher(72, 64, 8);
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(List.of(first, second), chunks, rail -> true, 4, 1);

    SwitcherNodeAudit.Result result = runToEnd(audit, chunks);

    assertEquals(List.of(first), result.stale());
    assertEquals(List.of(second), result.unverified());
    assertEquals(1, chunks.loadRequests.size());
  }

  @Test
  @DisplayName("区块加载失败或判定抛异常时保留节点，不中止整次核对")
  void failuresKeepTheNodeAndTheAuditContinues() {
    FakeChunks chunks = new FakeChunks();
    RailNodeRecord failedLoad = switcher(8, 64, 8);
    RailNodeRecord throwing = switcher(72, 64, 8);
    RailNodeRecord stale = switcher(136, 64, 8);
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(
            List.of(failedLoad, throwing, stale),
            chunks,
            rail -> {
              if (rail.x() == 72) {
                throw new IllegalStateException("TC 内部错误");
              }
              return true;
            },
            4,
            256);

    audit.step(FAR_FUTURE);
    chunks.fail(0, 0);
    SwitcherNodeAudit.Result result = runToEnd(audit, chunks);

    assertEquals(List.of(stale), result.stale());
    assertEquals(List.of(failedLoad, throwing), result.unverified());
    assertTrue(chunks.tickets.isEmpty());
  }

  @Test
  @DisplayName("非道岔节点不核对，相邻道岔共用区块时只加载一次")
  void skipsOtherNodesAndSharesChunksBetweenNeighbours() {
    FakeChunks chunks = new FakeChunks();
    RailNodeRecord station =
        new RailNodeRecord(
            WORLD,
            NodeId.of("OP:S:ST:1"),
            NodeType.STATION,
            8,
            64,
            8,
            Optional.empty(),
            Optional.empty());
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(
            List.of(station, switcher(4, 64, 4), switcher(9, 64, 9)),
            chunks,
            rail -> false,
            4,
            256);

    SwitcherNodeAudit.Result result = runToEnd(audit, chunks);

    assertTrue(result.stale().isEmpty());
    assertEquals(2, result.audited());
    assertEquals(1, chunks.loadRequests.size(), "两个道岔在同一区块，只加载一次");
  }

  @Test
  @DisplayName("关闭后不再挂新票，已挂的全部撤掉")
  void closeReleasesEverythingAndIgnoresLateLoads() {
    FakeChunks chunks = new FakeChunks();
    chunks.loaded.add(FakeChunks.key(0, 0));
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(
            List.of(switcher(8, 64, 8), switcher(72, 64, 8)), chunks, rail -> false, 4, 256);
    audit.step(0L);

    audit.close();
    chunks.completeAll();
    audit.step(FAR_FUTURE);

    assertTrue(chunks.tickets.isEmpty());
  }
}
