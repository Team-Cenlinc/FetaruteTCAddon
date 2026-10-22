package org.fetarute.fetaruteTCAddon.integration.traincarts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentManager;
import com.bergerkiller.bukkit.tc.controller.functions.TransferFunction;
import com.bergerkiller.bukkit.tc.controller.functions.TransferFunctionHost;
import com.bergerkiller.bukkit.tc.controller.functions.inputs.TransferFunctionInput.ReferencedSource;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 「附近有玩家」输入：按观看者距离出 0/1、同列车乘客算在旁、离开带回差、隔 tick 重判、配置读写。
 *
 * <p>TrainCarts 的传递函数注册表与 BKCommonLib 配置节点的类型转换都要真服务器环境，单测里直接调序列化器、配置节点用 mock；登记与 Unknown 回退只能实服验证。
 */
class TransferFunctionInputPlayerNearbyTest {

  // Location 只弱引用世界，测试里的世界必须有强引用，否则可能被回收成“World unloaded”。
  private final World world = mock(World.class);
  private final World otherWorld = mock(World.class);
  private final List<Player> viewers = new ArrayList<>();
  private final List<ReferencedSource> sources = new ArrayList<>();
  private Attachment attachment;
  private TransferFunctionHost host;

  @BeforeEach
  void setUp() {
    AttachmentManager manager = mock(AttachmentManager.class);
    when(manager.getWorld()).thenReturn(world);
    attachment = mock(Attachment.class);
    when(attachment.getManager()).thenReturn(manager);
    when(attachment.getViewers()).thenReturn(viewers);
    when(attachment.getTransform()).thenReturn(Matrix4x4.translation(100.0, 64.0, 100.0));

    host = mock(TransferFunctionHost.class);
    when(host.isAttachment()).thenReturn(true);
    when(host.getAttachment()).thenReturn(attachment);
    when(host.registerInputSource(any()))
        .thenAnswer(
            invocation -> {
              ReferencedSource source = invocation.getArgument(0);
              sources.add(source);
              return source;
            });
  }

  @Test
  void outputsZeroWithoutViewers() {
    TransferFunction function = TransferFunctionInputPlayerNearby.SERIALIZER.createNew(host);

    tick();

    assertEquals(0.0, function.map(0.0));
  }

  @Test
  void outputsOneWhenAViewerIsInsideTheRadius() {
    TransferFunction function = TransferFunctionInputPlayerNearby.SERIALIZER.createNew(host);
    viewers.add(viewerAt(world, 100.0 + 31.0, 64.0, 100.0));

    tick();

    assertEquals(1.0, function.map(0.0));
  }

  @Test
  void ignoresViewersOutsideTheRadiusOrInAnotherWorld() {
    TransferFunction function = TransferFunctionInputPlayerNearby.SERIALIZER.createNew(host);
    viewers.add(viewerAt(world, 100.0, 64.0 + 33.0, 100.0));
    viewers.add(viewerAt(otherWorld, 100.0, 64.0, 100.0));

    tick();

    assertEquals(0.0, function.map(0.0));
  }

  @Test
  void leavingNeedsTheExitMarginBeforeSwitchingOff() {
    TransferFunction function = TransferFunctionInputPlayerNearby.SERIALIZER.createNew(host);
    Location position = new Location(world, 100.0 + 30.0, 64.0, 100.0);
    viewers.add(viewerAt(position));

    tick();
    assertEquals(1.0, function.map(0.0));

    position.setX(100.0 + 34.0);
    tick();
    assertEquals(1.0, function.map(0.0), "仍在回差内，保持有人");

    position.setX(100.0 + 37.0);
    tick();
    assertEquals(0.0, function.map(0.0), "退出回差外，改判无人");

    position.setX(100.0 + 34.0);
    tick();
    assertEquals(0.0, function.map(0.0), "重新进入要回到半径内");
  }

  @Test
  void loadReadsTheSavedRadius() {
    ConfigurationNode config = mock(ConfigurationNode.class);
    when(config.getOrDefault("radius", TransferFunctionInputPlayerNearby.DEFAULT_RADIUS))
        .thenReturn(16.0);

    TransferFunctionInputPlayerNearby function =
        TransferFunctionInputPlayerNearby.SERIALIZER.load(host, config);

    assertEquals(16.0, function.radius());
    viewers.add(viewerAt(world, 100.0 + 20.0, 64.0, 100.0));
    tick();
    assertEquals(0.0, function.map(0.0));
  }

  @Test
  void saveWritesTheRadius() {
    ConfigurationNode config = mock(ConfigurationNode.class);

    TransferFunctionInputPlayerNearby.SERIALIZER.save(
        host, config, new TransferFunctionInputPlayerNearby(24.0));

    verify(config).set("radius", 24.0);
  }

  @Test
  void radiusIsClampedToTheSupportedRange() {
    assertEquals(
        TransferFunctionInputPlayerNearby.MIN_RADIUS,
        new TransferFunctionInputPlayerNearby(0.0).radius());
    assertEquals(
        TransferFunctionInputPlayerNearby.MAX_RADIUS,
        new TransferFunctionInputPlayerNearby(1000.0).radius());
    assertEquals(
        TransferFunctionInputPlayerNearby.DEFAULT_RADIUS,
        new TransferFunctionInputPlayerNearby(Double.NaN).radius());
  }

  @Test
  void hostWithoutAttachmentOutputsZero() {
    when(host.getAttachment()).thenReturn(null);

    TransferFunction function = TransferFunctionInputPlayerNearby.SERIALIZER.createNew(host);

    assertEquals(0.0, function.map(0.0));
    assertTrue(sources.stream().allMatch(source -> source == ReferencedSource.NONE));
  }

  @Test
  void cloneKeepsTheRadiusAndSharesTheSource() {
    TransferFunctionInputPlayerNearby original = new TransferFunctionInputPlayerNearby(20.0);
    original.updateSource(host);
    viewers.add(viewerAt(world, 100.0 + 10.0, 64.0, 100.0));
    tick();

    TransferFunctionInputPlayerNearby copy =
        assertInstanceOf(TransferFunctionInputPlayerNearby.class, original.clone());

    assertEquals(20.0, copy.radius());
    assertEquals(1.0, copy.map(0.0));
  }

  @Test
  void sourcesWithTheSameRadiusOnTheSameAttachmentAreEqual() {
    var a = new TransferFunctionInputPlayerNearby.NearbySource(attachment, 32.0);
    var b = new TransferFunctionInputPlayerNearby.NearbySource(attachment, 32.0);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, new TransferFunctionInputPlayerNearby.NearbySource(attachment, 16.0));
    assertNotEquals(
        a, new TransferFunctionInputPlayerNearby.NearbySource(mock(Attachment.class), 32.0));
  }

  @Test
  void ridersOfTheSameTrainCountAsNearbyWhereverTheySit() {
    Player rider = viewerAt(world, 100.0 + 180.0, 64.0, 100.0);
    viewers.add(rider);
    var source =
        new TransferFunctionInputPlayerNearby.NearbySource(attachment, 32.0, rider::equals);

    source.onTick();

    assertEquals(1.0, source.value());
  }

  @Test
  void refreshesOncePerInterval() {
    var source = new TransferFunctionInputPlayerNearby.NearbySource(attachment, 32.0);
    source.onTick();
    viewers.add(viewerAt(world, 100.0 + 10.0, 64.0, 100.0));

    for (int i = 1; i < TransferFunctionInputPlayerNearby.REFRESH_TICKS; i++) {
      source.onTick();
      assertEquals(0.0, source.value(), "间隔内沿用上次结果");
    }
    source.onTick();
    assertEquals(1.0, source.value());
  }

  /** 推进一个刷新间隔，每次恰好重判一次。 */
  private void tick() {
    for (int i = 0; i < TransferFunctionInputPlayerNearby.REFRESH_TICKS; i++) {
      sources.forEach(ReferencedSource::onTick);
    }
  }

  private static Player viewerAt(World world, double x, double y, double z) {
    return viewerAt(new Location(world, x, y, z));
  }

  private static Player viewerAt(Location position) {
    Player player = mock(Player.class);
    when(player.getWorld()).thenAnswer(invocation -> position.getWorld());
    when(player.getX()).thenAnswer(invocation -> position.getX());
    when(player.getY()).thenAnswer(invocation -> position.getY());
    when(player.getZ()).thenAnswer(invocation -> position.getZ());
    return player;
  }
}
