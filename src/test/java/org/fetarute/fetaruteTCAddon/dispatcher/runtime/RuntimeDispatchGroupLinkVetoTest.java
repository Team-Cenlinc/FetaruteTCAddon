package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.GroupLinkEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.EventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TrainCarts 默认把相撞的两列车联挂成一个编组，并把一方的属性整个覆盖到另一方、对调列车名。
 *
 * <p>2026-09-30 实服：出库口叠放的多列车同坐标复原后被联挂，tag 属主、TrainCarts 名与车厢数彼此错位，随后拆分出 {@code ~a..~k}
 * 一串碎片。受管列车之间由信号互斥，不存在需要联挂的场景，因此跨属主的联挂一律否决。
 */
class RuntimeDispatchGroupLinkVetoTest {

  private RuntimeDispatchService service;
  private RuntimeDispatchListener listener;
  private final List<String> diagnostics = new ArrayList<>();
  private final AtomicLong nowMillis = new AtomicLong(1_000L);

  @BeforeEach
  void setUp() {
    service = mock(RuntimeDispatchService.class);
    listener =
        new RuntimeDispatchListener(service, Runnable::run, diagnostics::add, nowMillis::get);
  }

  @Test
  void linkBetweenTwoDifferentFtaTrainsIsVetoed() {
    RuntimeTrainHandle ds = ftaTrain("SURC-DS-LW-0775", "SURC-DS-LW-0775");
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-7942", "SURC-MT-LP-7942");

    assertTrue(listener.shouldVetoGroupLink(ds, mt));
  }

  @Test
  void fragmentsOfTheSameOwnerMayRelink() {
    RuntimeTrainHandle whole = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498");
    RuntimeTrainHandle fragment = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498~a");
    RuntimeTrainHandle lowerCase = ftaTrain("surc-mt-lp-2498", "surc-mt-lp-2498~b");

    assertFalse(listener.shouldVetoGroupLink(whole, fragment));
    assertFalse(listener.shouldVetoGroupLink(fragment, lowerCase));
  }

  /** 实服 09:15：DS-LW-5925 被 TrainCarts 命名成 MT-LP-2498~k——名字属于 MT，tag 属于 DS，不能算同一列车。 */
  @Test
  void corruptedNameDoesNotMakeDifferentOwnersLookIdentical() {
    RuntimeTrainHandle ds = ftaTrain("SURC-DS-LW-5925", "SURC-MT-LP-2498~k");
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498");

    assertTrue(listener.shouldVetoGroupLink(ds, mt));
  }

  @Test
  void crossOwnerLinkIsCancelledAndLeavesAnUnconditionalTrace() {
    RuntimeTrainHandle ds = ftaTrain("SURC-DS-LW-0775", "SURC-DS-LW-0775");
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-7942", "SURC-MT-LP-7942");
    List<Boolean> cancelled = new ArrayList<>();

    listener.vetoLinkIfCrossOwner(ds, mt, cancelled::add);

    assertEquals(List.of(true), cancelled);
    assertEquals(1, diagnostics.size());
    String line = diagnostics.get(0);
    assertTrue(line.startsWith("SMART_GROUP_LINK_VETOED "), line);
    assertTrue(line.contains("SURC-DS-LW-0775") && line.contains("SURC-MT-LP-7942"), line);
  }

  /** 被否决的联挂不会完成，叠着的两个编组每个物理 tick 都会再撞：每次都要取消，但证据只留一条。 */
  @Test
  void repeatedCollisionOfTheSamePairIsAlwaysCancelledButTracedOnce() {
    RuntimeTrainHandle ds = ftaTrain("SURC-DS-LW-0775", "SURC-DS-LW-0775");
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-7942", "SURC-MT-LP-7942");
    List<Boolean> cancelled = new ArrayList<>();

    for (int tick = 0; tick < 5; tick++) {
      nowMillis.addAndGet(50L);
      listener.vetoLinkIfCrossOwner(ds, mt, cancelled::add);
    }
    // 对调两侧顺序仍是同一对。
    listener.vetoLinkIfCrossOwner(mt, ds, cancelled::add);

    assertEquals(6, cancelled.size());
    assertEquals(1, diagnostics.size());
  }

  @Test
  void traceReturnsAfterTheWindowAndReportsHowManyWereSuppressed() {
    RuntimeTrainHandle ds = ftaTrain("SURC-DS-LW-0775", "SURC-DS-LW-0775");
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-7942", "SURC-MT-LP-7942");

    listener.vetoLinkIfCrossOwner(ds, mt, cancelled -> {});
    listener.vetoLinkIfCrossOwner(ds, mt, cancelled -> {});
    listener.vetoLinkIfCrossOwner(ds, mt, cancelled -> {});
    nowMillis.addAndGet(31_000L);
    listener.vetoLinkIfCrossOwner(ds, mt, cancelled -> {});

    assertEquals(2, diagnostics.size());
    assertTrue(diagnostics.get(0).contains("suppressed=0"), diagnostics.get(0));
    assertTrue(diagnostics.get(1).contains("suppressed=2"), diagnostics.get(1));
  }

  /** 被否决的联挂不能再触发“预期拓扑变化”的全局 STOP_FIRST 恢复：否决必须先于恢复处理器执行，且恢复处理器必须忽略已取消事件。 */
  @Test
  void vetoRunsBeforeTheRecoveryHandlerWhichIgnoresCancelledEvents() throws Exception {
    EventHandler veto =
        RuntimeDispatchListener.class
            .getMethod("onGroupLinkVeto", GroupLinkEvent.class)
            .getAnnotation(EventHandler.class);
    EventHandler recovery =
        RuntimeDispatchListener.class
            .getMethod("onGroupLink", GroupLinkEvent.class)
            .getAnnotation(EventHandler.class);

    assertTrue(veto.ignoreCancelled());
    assertTrue(veto.priority().getSlot() < recovery.priority().getSlot());
    assertTrue(recovery.ignoreCancelled());
  }

  @Test
  void sameOwnerLinkIsLeftAloneAndSilent() {
    RuntimeTrainHandle whole = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498");
    RuntimeTrainHandle fragment = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498~a");
    List<Boolean> cancelled = new ArrayList<>();

    listener.vetoLinkIfCrossOwner(whole, fragment, cancelled::add);

    assertTrue(cancelled.isEmpty());
    assertTrue(diagnostics.isEmpty());
  }

  /**
   * 属主要 tag 与 TrainCarts 名两个维度都一致才算同一列车。只看 tag 会放过“tag 相同、名字却是毫不相干的两个名字”—— 那正是被联挂对调过一次的现场。（运行时的
   * {@code resolveTrackedTrainName} 在两者不一致时改以 TC 名为准，这里刻意不同： 以它为准会把 tag=DS、名=MT~k 的车判成与 MT
   * 同属主而放行联挂。）
   */
  @Test
  void sameTagButUnrelatedNamesIsNotTheSameTrain() {
    RuntimeTrainHandle mt = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498");
    RuntimeTrainHandle swapped = ftaTrain("SURC-MT-LP-2498", "SURC-DS-LW-5925");

    assertTrue(listener.shouldVetoGroupLink(mt, swapped));
  }

  @Test
  void managedTrainWithoutOwnerTagFallsBackToItsNameAndMatchesTheTaggedOne() {
    RuntimeTrainHandle tagged = ftaTrain("SURC-MT-LP-2498", "SURC-MT-LP-2498");
    RuntimeTrainHandle untagged = ftaTrain(null, "SURC-MT-LP-2498~a");

    assertFalse(listener.shouldVetoGroupLink(tagged, untagged));
  }

  /** 判定本身出错时保持 TrainCarts 原行为（不取消），只留一条证据——默认取消会误伤玩家自己的车厢。 */
  @Test
  void aFailingVetoCheckLeavesTheLinkAloneAndSaysSo() {
    RuntimeTrainHandle broken = mock(RuntimeTrainHandle.class);
    when(broken.properties()).thenThrow(new IllegalStateException("group-unloaded"));
    RuntimeTrainHandle other = ftaTrain("SURC-MT-LP-7942", "SURC-MT-LP-7942");
    List<Boolean> cancelled = new ArrayList<>();

    listener.vetoLinkIfCrossOwner(broken, other, cancelled::add);

    assertTrue(cancelled.isEmpty());
    assertEquals(1, diagnostics.size());
    assertTrue(diagnostics.get(0).startsWith("SMART_GROUP_LINK_VETO_ERROR "), diagnostics.get(0));
  }

  @Test
  void eventWithoutGroupsIsIgnored() {
    GroupLinkEvent event = new GroupLinkEvent(null, null);

    listener.onGroupLinkVeto(event);

    assertFalse(event.isCancelled());
    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void linkWithAnUnmanagedTrainKeepsTrainCartsBehaviour() {
    RuntimeTrainHandle managed = ftaTrain("SURC-DS-LW-0775", "SURC-DS-LW-0775");
    RuntimeTrainHandle unmanaged = unmanagedTrain("player-cart");

    assertFalse(listener.shouldVetoGroupLink(managed, unmanaged));
    assertFalse(listener.shouldVetoGroupLink(unmanaged, managed));
    assertFalse(listener.shouldVetoGroupLink(unmanaged, unmanagedTrain("other-cart")));
  }

  /** 两侧都是受管列车却认不出属主：无法证明是同一列车，宁可不联挂。 */
  @Test
  void managedTrainsWithUnknownOwnerAreVetoed() {
    RuntimeTrainHandle first = ftaTrain(null, "");
    RuntimeTrainHandle second = ftaTrain(null, "");

    assertTrue(listener.shouldVetoGroupLink(first, second));
  }

  private RuntimeTrainHandle unmanagedTrain(String currentName) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(currentName);
    RuntimeTrainHandle handle = mock(RuntimeTrainHandle.class);
    when(handle.properties()).thenReturn(properties);
    return handle;
  }

  /** 受管列车：{@code FTA_TRAIN_NAME} 标签为 {@code tagOwner}，TrainCarts 当前名为 {@code currentName}。 */
  private RuntimeTrainHandle ftaTrain(String tagOwner, String currentName) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(currentName);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(
            tagOwner == null
                ? List.of("FTA_ROUTE_INDEX=0")
                : List.of("FTA_TRAIN_NAME=" + tagOwner, "FTA_ROUTE_INDEX=0"));
    when(service.hasFtaRuntimeTag(properties)).thenReturn(true);
    RuntimeTrainHandle handle = mock(RuntimeTrainHandle.class);
    when(handle.properties()).thenReturn(properties);
    return handle;
  }
}
