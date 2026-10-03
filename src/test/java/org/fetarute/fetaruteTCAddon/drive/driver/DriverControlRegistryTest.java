package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DriverControlTags;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverControlRegistry 控制权登记")
class DriverControlRegistryTest {

  private final UUID player = UUID.randomUUID();
  private final DriverControlRegistry registry = new DriverControlRegistry();

  /** 带真实标签列表的列车属性。 */
  private static TrainProperties properties(String name) {
    List<String> tags = new ArrayList<>();
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
    when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
    doAnswer(
            inv -> {
              for (Object arg : inv.getArguments()) {
                if (arg instanceof String s) {
                  tags.add(s);
                }
              }
              return null;
            })
        .when(properties)
        .addTags(any(String[].class));
    doAnswer(
            inv -> {
              for (Object arg : inv.getArguments()) {
                if (arg instanceof String s) {
                  tags.remove(s);
                }
              }
              return null;
            })
        .when(properties)
        .removeTags(any(String[].class));
    return properties;
  }

  private DriverLink link(String name, TrainProperties properties) {
    return new DriverLink(player, name, properties, () -> 0.0, () -> 0L);
  }

  @Test
  @DisplayName("登记写入驾驶员标签，解除时清掉")
  void bindAndUnbindManageTheTag() {
    TrainProperties properties = properties("T-1");
    DriverLink link = link("T-1", properties);

    registry.bind(properties, link);
    assertTrue(registry.isDriverControlled(properties));
    assertTrue(registry.isDriverControlledName("T-1"));
    assertEquals(player, DriverControlTags.driver(properties).orElseThrow());

    registry.unbind(link);
    assertFalse(registry.isDriverControlled(properties));
    assertFalse(registry.isDriverControlledName("T-1"));
    assertFalse(DriverControlTags.present(properties));
    assertTrue(registry.isEmpty());
  }

  @Test
  @DisplayName("属性对象被换掉：车名与标签一致时重新挂上")
  void replacedPropertiesAreReattachedByNameAndTag() {
    TrainProperties original = properties("T-2");
    DriverLink link = link("T-2", original);
    registry.bind(original, link);

    TrainProperties replaced = properties("T-2");
    DriverControlTags.write(replaced, player);
    assertTrue(registry.isDriverControlled(replaced));
    assertSame(replaced, link.properties());

    TrainProperties stranger = properties("T-2");
    DriverControlTags.write(stranger, UUID.randomUUID());
    assertFalse(registry.isDriverControlled(stranger), "标签里的驾驶员不一致时不认");
  }

  @Test
  @DisplayName("只有标签没有登记（例如崩服后）：按自动运行处理")
  void tagWithoutBindingIsAutomatic() {
    TrainProperties properties = properties("T-3");
    DriverControlTags.write(properties, player);
    assertFalse(registry.isDriverControlled(properties));
  }

  @Test
  @DisplayName("ATO 下物理控车归自动运行，但仍算有驾驶员在岗")
  void atoIsNotPhysicalControl() {
    TrainProperties properties = properties("T-4");
    DriverLink link = link("T-4", properties);
    registry.bind(properties, link);
    link.setMode(DrivingMode.ATO);

    assertFalse(registry.isDriverControlled(properties));
    assertFalse(registry.isDriverControlledName("T-4"), "健康层照常恢复 ATO 车");
    assertTrue(registry.hasDriver("T-4"), "但车上仍有驾驶员：死锁时先请他交还");
  }

  @Test
  @DisplayName("指令交给链路，中断与交还请求转给处理器")
  void routesDirectivesInterruptsAndHandbacks() {
    TrainProperties properties = properties("T-5");
    DriverLink link = link("T-5", properties);
    registry.bind(properties, link);
    List<String> events = new ArrayList<>();
    registry.setHandler(
        new DriverControlRegistry.Handler() {
          @Override
          public void onInterrupt(DriverLink target, DriverInterrupt interrupt) {
            events.add(target.trainName() + ":" + interrupt);
          }

          @Override
          public void onHandbackRequested(DriverLink target, String reason) {
            events.add(target.trainName() + ":handback:" + reason);
          }
        });
    DriverDirective directive =
        new DriverDirective(
            SignalAspect.PROCEED,
            StopControlMode.BRAKING_TO_PLANNED_STOP,
            10.0,
            10.0,
            true,
            OptionalLong.empty(),
            null);

    registry.publish(properties, directive);
    registry.interrupt(properties, DriverInterrupt.EMERGENCY);
    registry.requestHandback("T-5", "deadlock");
    registry.requestHandback("other", "deadlock");

    assertSame(directive, link.directive());
    assertEquals(List.of("T-5:EMERGENCY", "T-5:handback:deadlock"), events);
  }
}
