package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 车站停靠事件的多路分发。
 *
 * <p>{@link StationStopCoordinator} 只有一个观察者槽位；时刻表、HUD 在站判定与公开 API 事件都需要同一份事实， 于是由本类占住那个槽位再按名字分发。
 * 按名字注册是为了让 {@code /fta reload} 重复注册时整体替换，而不是越挂越多。
 *
 * <p>每个观察者单独隔离异常：一个观察者抛错不能让排在它后面的观察者收不到事件。 观察者契约（不阻塞、不改列车状态）见 {@link StationStopObserver}。
 */
public final class StationStopObserverHub implements StationStopObserver {

  private final Map<String, StationStopObserver> observers = new ConcurrentHashMap<>();
  private final Consumer<String> debugLogger;

  public StationStopObserverHub(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 按名字注册；同名注册会替换旧的观察者。{@code observer} 为 null 时等同于注销。 */
  public void register(String key, StationStopObserver observer) {
    Objects.requireNonNull(key, "key");
    if (observer == null) {
      observers.remove(key);
      return;
    }
    observers.put(key, observer);
  }

  /** 注销指定名字的观察者。 */
  public void unregister(String key) {
    if (key != null) {
      observers.remove(key);
    }
  }

  @Override
  public void onStationArrival(StationStopEvent event) {
    observers.forEach(
        (key, observer) -> {
          try {
            observer.onStationArrival(event);
          } catch (RuntimeException ex) {
            debugLogger.accept("STATION_STOP_OBSERVER_FAILED observer=" + key + " error=" + ex);
          }
        });
  }

  @Override
  public void onStationDeparture(StationStopEvent event) {
    observers.forEach(
        (key, observer) -> {
          try {
            observer.onStationDeparture(event);
          } catch (RuntimeException ex) {
            debugLogger.accept("STATION_STOP_OBSERVER_FAILED observer=" + key + " error=" + ex);
          }
        });
  }

  @Override
  public void onTrainReleased(String trainName, String reason) {
    observers.forEach(
        (key, observer) -> {
          try {
            observer.onTrainReleased(trainName, reason);
          } catch (RuntimeException ex) {
            debugLogger.accept("STATION_STOP_OBSERVER_FAILED observer=" + key + " error=" + ex);
          }
        });
  }

  @Override
  public void onPlatformResolved(PlatformResolution resolution) {
    observers.forEach(
        (key, observer) -> {
          try {
            observer.onPlatformResolved(resolution);
          } catch (RuntimeException ex) {
            debugLogger.accept("STATION_STOP_OBSERVER_FAILED observer=" + key + " error=" + ex);
          }
        });
  }
}
