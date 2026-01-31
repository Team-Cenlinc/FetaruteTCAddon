package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

/**
 * TrainHudContextResolver 的 track 解析功能测试。
 *
 * <p>覆盖场景：
 *
 * <ul>
 *   <li>4 段站点格式 {@code Op:S:Station:Track}
 *   <li>4 段车库格式 {@code Op:D:Depot:Track}
 *   <li>5 段站咽喉格式 {@code Op:S:Station:Track:Seq}
 *   <li>5 段区间格式 {@code Op:From:To:Track:Seq}
 *   <li>无效/空输入
 * </ul>
 */
class TrainHudContextResolverTrackTest {

  @Test
  void resolveTrackFromNodeId_4SegmentStation_returnsTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:S:PTK:1"));
    assertEquals("1", result);
  }

  @Test
  void resolveTrackFromNodeId_4SegmentStation_multiDigitTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:S:PTK:12"));
    assertEquals("12", result);
  }

  @Test
  void resolveTrackFromNodeId_4SegmentDepot_returnsTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:D:LVT:2"));
    assertEquals("2", result);
  }

  @Test
  void resolveTrackFromNodeId_5SegmentStationThroat_returnsTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:S:PTK:3:001"));
    assertEquals("3", result);
  }

  @Test
  void resolveTrackFromNodeId_5SegmentDepotThroat_returnsTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:D:LVT:4:002"));
    assertEquals("4", result);
  }

  @Test
  void resolveTrackFromNodeId_5SegmentInterval_returnsTrack() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURC:OFL:MLU:2:004"));
    assertEquals("2", result);
  }

  @Test
  void resolveTrackFromNodeId_nullInput_returnsDash() throws Exception {
    String result = invokeResolveTrack(null);
    assertEquals("-", result);
  }

  @Test
  void resolveTrackFromNodeId_emptyValue_returnsDash() throws Exception {
    String result = invokeResolveTrack(NodeId.of(""));
    assertEquals("-", result);
  }

  @Test
  void resolveTrackFromNodeId_insufficientSegments_returnsDash() throws Exception {
    String result = invokeResolveTrack(NodeId.of("SURN:S:PTK"));
    assertEquals("-", result);
  }

  @Test
  void resolveTrackFromNodeId_nonNumericTrack_returnsDash() throws Exception {
    // 若 track 段不是数字，应返回 "-"
    String result = invokeResolveTrack(NodeId.of("SURN:S:PTK:ABC"));
    assertEquals("-", result);
  }

  /**
   * 通过反射调用 private 方法 resolveTrackFromNodeId。
   *
   * <p>注：由于该方法为 private，测试通过反射访问。
   */
  private String invokeResolveTrack(NodeId nodeId) throws Exception {
    TrainHudContextResolver resolver =
        new TrainHudContextResolver(
            mock(FetaruteTCAddon.class),
            mock(LocaleManager.class),
            mock(EtaService.class),
            mock(RouteDefinitionCache.class),
            mock(RouteProgressRegistry.class),
            mock(LayoverRegistry.class),
            mock(HudTemplateService.class),
            msg -> {});
    Method method =
        TrainHudContextResolver.class.getDeclaredMethod("resolveTrackFromNodeId", NodeId.class);
    method.setAccessible(true);
    return (String) method.invoke(resolver, nodeId);
  }
}
