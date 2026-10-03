package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.function.Supplier;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

/**
 * 经插件实例转发的控制权。
 *
 * <p>执行层对象（控车执行器、TrainCarts 句柄）在很多地方临时创建，拿不到插件引用；这里第一次使用时找到插件，之后每次转发给它当前的控制权。
 * 找不到插件（单元测试、插件未启用）时一律视为自动运行；转发出现异常时也按自动运行处理，不让控制权问题把列车冻住。
 */
final class PluginControlAuthority implements ControlAuthority {

  static final PluginControlAuthority INSTANCE = new PluginControlAuthority();

  private volatile boolean resolved;
  private volatile Supplier<ControlAuthority> source;

  private PluginControlAuthority() {}

  private ControlAuthority delegate() {
    if (!resolved) {
      resolve();
    }
    Supplier<ControlAuthority> current = source;
    if (current == null) {
      return NONE;
    }
    ControlAuthority authority = current.get();
    return authority == null ? NONE : authority;
  }

  private synchronized void resolve() {
    if (resolved) {
      return;
    }
    try {
      JavaPlugin plugin = JavaPlugin.getProvidingPlugin(ControlAuthority.class);
      if (plugin instanceof FetaruteTCAddon addon) {
        source = addon::getControlAuthority;
      }
    } catch (RuntimeException | LinkageError ex) {
      source = null;
    }
    resolved = true;
  }

  @Override
  public boolean isDriverControlled(TrainProperties properties) {
    try {
      return properties != null && delegate().isDriverControlled(properties);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  @Override
  public boolean isDriverControlledName(String trainName) {
    try {
      return trainName != null && delegate().isDriverControlledName(trainName);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  @Override
  public void publish(TrainProperties properties, DriverDirective directive) {
    try {
      delegate().publish(properties, directive);
    } catch (RuntimeException ignored) {
      // 交给驾驶员失败时由驾驶侧的指令过期保护兜底。
    }
  }

  @Override
  public void interrupt(TrainProperties properties, DriverInterrupt interrupt) {
    try {
      delegate().interrupt(properties, interrupt);
    } catch (RuntimeException ignored) {
      // 同上。
    }
  }

  @Override
  public void requestHandback(String trainName, String reason) {
    try {
      delegate().requestHandback(trainName, reason);
    } catch (RuntimeException ignored) {
      // 同上。
    }
  }

  @Override
  public void beginStationStop(TrainProperties properties, DriverStationStop stop) {
    try {
      delegate().beginStationStop(properties, stop);
    } catch (RuntimeException ignored) {
      // 同上。
    }
  }
}
