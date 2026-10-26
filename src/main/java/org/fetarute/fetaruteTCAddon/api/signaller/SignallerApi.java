package org.fetarute.fetaruteTCAddon.api.signaller;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 车站信号员 API（1.15.0）：各信号楼（控制区）的模式与值守者、在入口等信号员的车、给出的许可与扣着的车。
 *
 * <p>只查询，不代玩家上岗或操作控制台。信号员在墙上的控制台（布局 {@code signalbox-*}、绑定控制区的站台屏）上岗；有人值守时控制区为人工，
 * 进入控制区的车要信号员排列进路，没人值守时为自动、调度照常放行。人工控制只在内存里，重启与重载一律回到自动。上岗与下岗另有事件 {@code
 * SignallerDutyStartEvent}、{@code SignallerDutyEndedEvent}。
 *
 * <p>线程：所有方法都可在任意线程调用，读的是信号员模块每秒巡检与每次操作后发布的快照（最多约一秒前）；返回值都是不可变快照。
 *
 * <p>信号员功能未加载（{@code config.yml} 的 {@code signaller.enabled} 关闭或模块初始化失败）时 {@link #enabled()} 为
 * {@code false}，查询返回空。
 */
public interface SignallerApi {

  /** 信号员功能是否可用。 */
  boolean enabled();

  /** 各信号楼的概况：有控制台在显示、被点过或有人值守过的楼。 */
  List<SignalBoxView> boxes();

  /**
   * 一座信号楼的概况。
   *
   * @param operatorCode 运营商代码（不分大小写）
   * @param zoneCode 控制区代码（不分大小写）
   */
  Optional<SignalBoxView> box(String operatorCode, String zoneCode);

  /** 这名玩家在值守的信号楼；没有在当信号员时为空。 */
  Optional<SignalBoxView> dutyOf(UUID playerId);

  /** 这列车此刻是否停在某个控制区入口等信号员排列进路（按列车名，不分大小写）。 */
  boolean waitingForSignaller(String trainName);

  /** 控制区模式。 */
  enum Mode {
    /** 调度照常放行（没人值守时也是这个）。 */
    AUTO,
    /** 进入控制区要先有信号员给的许可。 */
    MANUAL
  }

  /**
   * 一座信号楼。
   *
   * @param operatorCode 运营商代码
   * @param zoneCode 控制区代码
   * @param name 控制区名
   * @param mode 模式
   * @param signallerId 值守的信号员；没人值守时为空
   * @param signallerName 信号员名字（上岗时）
   * @param onDutySince 上岗时刻
   * @param waitingTrains 在入口等许可的车（列车名，按开始等的先后）
   * @param permits 已给、车还没驶离控制区的许可数
   * @param heldTrains 信号员扣在站里的车（列车名）
   * @param parentZoneCode 中心工位分出去的临时小区所属大区的代码：大区有人值守时后来的人在工位上分到一片，{@code zoneCode} 是它的临时代码（形如
   *     {@code 大区代码~2}，只在内存里），下岗后这座楼随之消失。普通控制区为空
   */
  record SignalBoxView(
      String operatorCode,
      String zoneCode,
      String name,
      Mode mode,
      Optional<UUID> signallerId,
      Optional<String> signallerName,
      Optional<Instant> onDutySince,
      List<String> waitingTrains,
      int permits,
      List<String> heldTrains,
      Optional<String> parentZoneCode) {

    public SignalBoxView {
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(zoneCode, "zoneCode");
      name = name == null ? zoneCode : name;
      Objects.requireNonNull(mode, "mode");
      signallerId = signallerId == null ? Optional.empty() : signallerId;
      signallerName = signallerName == null ? Optional.empty() : signallerName;
      onDutySince = onDutySince == null ? Optional.empty() : onDutySince;
      waitingTrains = waitingTrains == null ? List.of() : List.copyOf(waitingTrains);
      permits = Math.max(0, permits);
      heldTrains = heldTrains == null ? List.of() : List.copyOf(heldTrains);
      parentZoneCode = parentZoneCode == null ? Optional.empty() : parentZoneCode;
    }

    /** 普通控制区。 */
    public SignalBoxView(
        String operatorCode,
        String zoneCode,
        String name,
        Mode mode,
        Optional<UUID> signallerId,
        Optional<String> signallerName,
        Optional<Instant> onDutySince,
        List<String> waitingTrains,
        int permits,
        List<String> heldTrains) {
      this(
          operatorCode,
          zoneCode,
          name,
          mode,
          signallerId,
          signallerName,
          onDutySince,
          waitingTrains,
          permits,
          heldTrains,
          Optional.empty());
    }
  }

  /** 信号员功能未加载时的占位实现。 */
  SignallerApi UNAVAILABLE =
      new SignallerApi() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public List<SignalBoxView> boxes() {
          return List.of();
        }

        @Override
        public Optional<SignalBoxView> box(String operatorCode, String zoneCode) {
          return Optional.empty();
        }

        @Override
        public Optional<SignalBoxView> dutyOf(UUID playerId) {
          return Optional.empty();
        }

        @Override
        public boolean waitingForSignaller(String trainName) {
          return false;
        }
      };
}
