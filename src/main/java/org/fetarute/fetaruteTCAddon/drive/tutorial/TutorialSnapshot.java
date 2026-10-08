package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.hud.DriverStationHint;

/**
 * 新手教程每次判定时看到的驾驶会话：只有值，不持有任何服务器对象。教程步骤与情境提示都只看它。
 *
 * @param setupMode 启动流程的操作方式（standard 一键启动、simulation 逐项接通）
 * @param dispatch 是否在驾驶调度列车
 * @param ato 是否为 ATO（自动运行操纵，驾驶员只确认发车）
 * @param setupReady 列车是否已启动
 * @param cab simulation 级的车上系统（气压、制动试验、警惕装置）是否启用
 * @param manualCompressor 压缩机是否要手动打开（机车牵引）
 * @param compressorOn 手动压缩机开关是否打开
 * @param brakeTestPassed 制动试验是否已通过（没有车上系统时视为通过）
 * @param parkingApplied 停放制动是否施加着
 * @param reverser 换向手柄位置
 * @param handle 手柄档位（驾驶员拉到的位置，不论牵引是否被封锁）
 * @param speedBps 车速（格/秒）
 * @param stopped 是否已停稳
 * @param doorsOpen 是否有车门开着
 * @param doorsClosing 关门动画是否还在放
 * @param signalAcknowledgePending 信号变严，正等驾驶员确认
 * @param stationHint 此刻的车站提示种类；没有时为 {@code null}
 * @param doorsRequired 停妥待开门时本站是否要开门
 * @param departurePending ATO 下站台正等驾驶员确认发车
 * @param vigilanceWarning 警惕装置正在报警
 */
public record TutorialSnapshot(
    SimulationLevel.SetupMode setupMode,
    boolean dispatch,
    boolean ato,
    boolean setupReady,
    boolean cab,
    boolean manualCompressor,
    boolean compressorOn,
    boolean brakeTestPassed,
    boolean parkingApplied,
    ReverserPosition reverser,
    Notch handle,
    double speedBps,
    boolean stopped,
    boolean doorsOpen,
    boolean doorsClosing,
    boolean signalAcknowledgePending,
    DriverStationHint.Kind stationHint,
    boolean doorsRequired,
    boolean departurePending,
    boolean vigilanceWarning) {

  public TutorialSnapshot {
    Objects.requireNonNull(setupMode, "setupMode");
    Objects.requireNonNull(reverser, "reverser");
    Objects.requireNonNull(handle, "handle");
  }

  /** 逐字段构造：默认是一辆停着、已启动、换向在前进、手柄在 N 的 standard 级非调度列车，按需改字段。 */
  public static Builder builder() {
    return new Builder();
  }

  /** 逐字段构造快照。 */
  public static final class Builder {
    private SimulationLevel.SetupMode setupMode = SimulationLevel.SetupMode.ONE_CLICK;
    private boolean dispatch;
    private boolean ato;
    private boolean setupReady = true;
    private boolean cab;
    private boolean manualCompressor;
    private boolean compressorOn = true;
    private boolean brakeTestPassed = true;
    private boolean parkingApplied;
    private ReverserPosition reverser = ReverserPosition.FORWARD;
    private Notch handle = Notch.N;
    private double speedBps;
    private boolean stopped = true;
    private boolean doorsOpen;
    private boolean doorsClosing;
    private boolean signalAcknowledgePending;
    private DriverStationHint.Kind stationHint;
    private boolean doorsRequired;
    private boolean departurePending;
    private boolean vigilanceWarning;

    private Builder() {}

    public Builder setupMode(SimulationLevel.SetupMode value) {
      this.setupMode = value;
      return this;
    }

    public Builder dispatch(boolean value) {
      this.dispatch = value;
      return this;
    }

    public Builder ato(boolean value) {
      this.ato = value;
      return this;
    }

    public Builder setupReady(boolean value) {
      this.setupReady = value;
      return this;
    }

    public Builder cab(boolean value) {
      this.cab = value;
      return this;
    }

    public Builder manualCompressor(boolean value) {
      this.manualCompressor = value;
      return this;
    }

    public Builder compressorOn(boolean value) {
      this.compressorOn = value;
      return this;
    }

    public Builder brakeTestPassed(boolean value) {
      this.brakeTestPassed = value;
      return this;
    }

    public Builder parkingApplied(boolean value) {
      this.parkingApplied = value;
      return this;
    }

    public Builder reverser(ReverserPosition value) {
      this.reverser = value;
      return this;
    }

    public Builder handle(Notch value) {
      this.handle = value;
      return this;
    }

    /** 车速（格/秒）；同时按它定停稳与否（不到 0.05 格/秒算停稳）。 */
    public Builder speedBps(double value) {
      this.speedBps = value;
      this.stopped = value <= 0.05;
      return this;
    }

    public Builder stopped(boolean value) {
      this.stopped = value;
      return this;
    }

    public Builder doorsOpen(boolean value) {
      this.doorsOpen = value;
      return this;
    }

    public Builder doorsClosing(boolean value) {
      this.doorsClosing = value;
      return this;
    }

    public Builder signalAcknowledgePending(boolean value) {
      this.signalAcknowledgePending = value;
      return this;
    }

    public Builder stationHint(DriverStationHint.Kind value) {
      this.stationHint = value;
      return this;
    }

    public Builder doorsRequired(boolean value) {
      this.doorsRequired = value;
      return this;
    }

    public Builder departurePending(boolean value) {
      this.departurePending = value;
      return this;
    }

    public Builder vigilanceWarning(boolean value) {
      this.vigilanceWarning = value;
      return this;
    }

    public TutorialSnapshot build() {
      return new TutorialSnapshot(
          setupMode,
          dispatch,
          ato,
          setupReady,
          cab,
          manualCompressor,
          compressorOn,
          brakeTestPassed,
          parkingApplied,
          reverser,
          handle,
          speedBps,
          stopped,
          doorsOpen,
          doorsClosing,
          signalAcknowledgePending,
          stationHint,
          doorsRequired,
          departurePending,
          vigilanceWarning);
    }
  }
}
