package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;

/** 测试用的控制权：指定的列车由驾驶员控制，并记下调度层发来的指令、中断与交还请求。 */
public final class RecordingControlAuthority implements ControlAuthority {

  private final List<TrainProperties> controlled = new ArrayList<>();
  private final List<String> controlledNames = new ArrayList<>();
  private final List<DriverDirective> directives = new ArrayList<>();
  private final List<DriverInterrupt> interrupts = new ArrayList<>();
  private final List<String> handbacks = new ArrayList<>();
  private final List<TrainProperties> turnbacks = new ArrayList<>();

  /** 让这列车由驾驶员控制。 */
  public RecordingControlAuthority control(TrainProperties properties) {
    controlled.add(properties);
    return this;
  }

  /** 让这个车名由驾驶员控制（只按车名判断的调用方用）。 */
  public RecordingControlAuthority controlName(String trainName) {
    controlledNames.add(trainName);
    return this;
  }

  /** 这列车下一次放行时要按发车方向调头（取走一次即清除）。 */
  public RecordingControlAuthority turnback(TrainProperties properties) {
    turnbacks.add(properties);
    return this;
  }

  @Override
  public boolean takeTurnback(TrainProperties properties) {
    return turnbacks.removeIf(candidate -> candidate == properties);
  }

  @Override
  public boolean isDriverControlled(TrainProperties properties) {
    for (TrainProperties candidate : controlled) {
      if (candidate == properties) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isDriverControlledName(String trainName) {
    return controlledNames.contains(trainName);
  }

  @Override
  public void publish(TrainProperties properties, DriverDirective directive) {
    directives.add(directive);
  }

  @Override
  public void interrupt(TrainProperties properties, DriverInterrupt interrupt) {
    interrupts.add(interrupt);
  }

  @Override
  public void requestHandback(String trainName, String reason) {
    handbacks.add(trainName + ":" + reason);
  }

  public List<DriverDirective> directives() {
    return List.copyOf(directives);
  }

  public List<DriverInterrupt> interrupts() {
    return List.copyOf(interrupts);
  }

  /** 交还请求，格式为“车名:原因”。 */
  public List<String> handbacks() {
    return List.copyOf(handbacks);
  }

  /** 最近一条指令。 */
  public DriverDirective lastDirective() {
    return directives.get(directives.size() - 1);
  }
}
