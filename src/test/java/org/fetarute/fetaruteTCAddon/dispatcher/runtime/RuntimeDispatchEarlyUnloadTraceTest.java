package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 出库不久就被 TrainCarts 卸载，是 2026-09-30 拆分事故的直接现场：卸载被当作移除，占用随即释放，下一班叠在同一锚点。
 *
 * <p>该现场当时没有留下任何日志（DEBUG 在 07:55 的重载后整段关闭，运行时只在“实体化生成未完成就被卸载”时才写），下一次必须留下证据。
 */
class RuntimeDispatchEarlyUnloadTraceTest {

  private static final long RUN_AT = 1_000_000L;

  private RuntimeDispatchService service;
  private RuntimeDispatchListener listener;
  private final List<String> diagnostics = new ArrayList<>();
  private final AtomicLong nowMillis = new AtomicLong(RUN_AT);

  @BeforeEach
  void setUp() {
    service = mock(RuntimeDispatchService.class);
    listener =
        new RuntimeDispatchListener(service, Runnable::run, diagnostics::add, nowMillis::get);
  }

  @Test
  void trainUnloadedSecondsAfterRunStartLeavesEvidence() {
    RuntimeTrainHandle train = ftaTrain("SURC-DS-LW-8125", String.valueOf(RUN_AT), false);
    nowMillis.set(RUN_AT + 4_000L);

    listener.traceEarlyUnload(train);

    assertEquals(1, diagnostics.size());
    String line = diagnostics.get(0);
    assertTrue(line.startsWith("SMART_FTA_EARLY_UNLOAD "), line);
    assertTrue(line.contains("train=SURC-DS-LW-8125"), line);
    assertTrue(line.contains("ageMs=4000"), line);
    assertTrue(line.contains("keepChunksLoaded=false"), line);
  }

  @Test
  void longRunningTrainUnloadingIsNotAnAnomaly() {
    RuntimeTrainHandle train = ftaTrain("SURC-MT-LP-2642", String.valueOf(RUN_AT), true);
    nowMillis.set(RUN_AT + 10 * 60_000L);

    listener.traceEarlyUnload(train);

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void trainWithoutRunStartTagIsIgnored() {
    RuntimeTrainHandle train = ftaTrain("SURC-MT-LP-2642", null, false);

    listener.traceEarlyUnload(train);

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void unmanagedTrainIsIgnored() {
    TrainProperties properties = mock(TrainProperties.class);
    RuntimeTrainHandle handle = mock(RuntimeTrainHandle.class);
    when(handle.properties()).thenReturn(properties);

    listener.traceEarlyUnload(handle);

    assertTrue(diagnostics.isEmpty());
  }

  private RuntimeTrainHandle ftaTrain(String name, String runAt, boolean keepChunksLoaded) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.isKeepingChunksLoaded()).thenReturn(keepChunksLoaded);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(
            runAt == null
                ? List.of("FTA_TRAIN_NAME=" + name)
                : List.of("FTA_TRAIN_NAME=" + name, "FTA_RUN_AT=" + runAt));
    when(service.hasFtaRuntimeTag(properties)).thenReturn(true);
    RuntimeTrainHandle handle = mock(RuntimeTrainHandle.class);
    when(handle.properties()).thenReturn(properties);
    return handle;
  }
}
