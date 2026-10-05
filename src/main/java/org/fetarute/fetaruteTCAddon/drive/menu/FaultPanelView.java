package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFaults;

/**
 * 菜单上的故障指示此刻的样子：当前有哪些故障、各自怎么处置。本类不依赖服务器对象，便于单测。
 *
 * @param faults 当前的故障（按枚举顺序）
 * @param breakerStage 主断跳闸后的复位进度；没有跳闸时为 {@code null}
 * @param doorBypass 门旁路是否接通
 */
public record FaultPanelView(
    List<CabFault> faults, CabFaults.BreakerStage breakerStage, boolean doorBypass) {

  public FaultPanelView {
    faults = List.copyOf(Objects.requireNonNull(faults, "faults"));
  }

  /** 按车上故障的当前状态取样子。 */
  public static FaultPanelView of(CabFaults faults) {
    return new FaultPanelView(
        faults.activeFaults(), faults.breakerStage().orElse(null), faults.doorBypassed());
  }

  /** 故障灯是否点亮：有故障，或门旁路接通着。 */
  public boolean lit() {
    return !faults.isEmpty() || doorBypass;
  }

  /** 名称的语言键。 */
  public String nameKey() {
    if (!faults.isEmpty()) {
      return "drive.menu.faults.active";
    }
    return doorBypass ? "drive.menu.faults.bypass-only" : "drive.menu.faults.none";
  }

  /** 贴图键（{@code fetarute:drive/} 之后的部分）。 */
  public String modelKey() {
    return MenuLayout.MODEL_PREFIX + (lit() ? "fault_on" : "fault_off");
  }

  /** 每个故障一行处置说明的语言键，门旁路接通时再加一行警示。 */
  public List<String> lineKeys() {
    List<String> keys = new ArrayList<>();
    for (CabFault fault : faults) {
      if (fault == CabFault.BREAKER_TRIP) {
        String stage =
            breakerStage == null ? "tripped" : breakerStage.name().toLowerCase(Locale.ROOT);
        keys.add("drive.menu.faults.breaker-trip-" + stage);
      } else {
        keys.add("drive.menu.faults." + fault.key());
      }
    }
    if (doorBypass) {
      keys.add("drive.menu.faults.door-bypass");
    }
    return List.copyOf(keys);
  }
}
