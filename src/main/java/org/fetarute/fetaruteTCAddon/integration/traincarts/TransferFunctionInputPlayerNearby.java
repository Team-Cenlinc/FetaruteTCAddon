package org.fetarute.fetaruteTCAddon.integration.traincarts;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.common.map.MapCanvas;
import com.bergerkiller.bukkit.common.map.MapColorPalette;
import com.bergerkiller.bukkit.common.map.MapFont;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.ui.MapWidgetNumberBox;
import com.bergerkiller.bukkit.tc.controller.functions.TransferFunction;
import com.bergerkiller.bukkit.tc.controller.functions.TransferFunctionHost;
import com.bergerkiller.bukkit.tc.controller.functions.inputs.TransferFunctionInput;
import com.bergerkiller.bukkit.tc.controller.functions.ui.MapWidgetTransferFunctionItem;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * TrainCarts 传递函数输入「In: Player Nearby」：附件半径内有玩家、或有玩家坐在这列车上时输出 1，否则输出 0。
 *
 * <p>用来给 Sequencer 的 autoplay 加一道门：在 autoplay 的函数列表末尾以 AND 追加本输入，无人在旁时 autoplay 为 0，Sequencer
 * 不排效果循环，也就不再逐帧推进各效果、求每个效果的 active/volume/pitch 函数。
 *
 * <p>只在附件的观看者（正在跟踪这节车的玩家）里找，不扫全服玩家，所以有效半径不超过实体跟踪距离。坐在同一列车其他车厢的乘客也算在旁：全列车可闻、在乘客位置播放的声音不能因车厢离得远而断。离开判定比进入多
 * {@link #EXIT_MARGIN} 格，玩家站在边界上时不会让 Sequencer 反复启停。
 *
 * <p>半径下限取原版音量 ≤ 1 的可闻距离：门开关会触发 START/STOP 组，这些一次性音效必须在听不见的距离发生。
 *
 * <p>必须在插件 {@code onLoad} 阶段 {@link #register()}：TrainCarts 启用时就恢复已加载区块里的列车并读取附件配置，晚于此登记的模型会把本输入读成
 * Unknown（原样输出输入值，autoplay 恒为 0），且附件配置不变时不会重读。
 */
public final class TransferFunctionInputPlayerNearby extends TransferFunctionInput {

  /** 写入模型配置的类型 ID；改名会让已保存的模型读成 Unknown。 */
  public static final String TYPE_ID = "FTA-INPUT-PLAYER-NEARBY";

  static final double DEFAULT_RADIUS = 32.0;
  static final double MIN_RADIUS = 16.0;
  static final double MAX_RADIUS = 128.0;

  /** 已判定有人时，玩家要退到半径外这么多格才改判无人。 */
  static final double EXIT_MARGIN = 4.0;

  /** 每隔多少 tick 重判一次；一节车常挂多个 Sequencer，各自逐 tick 扫同一批观看者是重复劳动，回差足以吸收这点延迟。 */
  static final int REFRESH_TICKS = 10;

  public static final Serializer<TransferFunctionInputPlayerNearby> SERIALIZER =
      new Serializer<>() {
        @Override
        public String typeId() {
          return TYPE_ID;
        }

        @Override
        public String title() {
          return "In: Player Nearby";
        }

        @Override
        public boolean isInput() {
          return true;
        }

        @Override
        public boolean isListed(TransferFunctionHost host) {
          return host.isAttachment();
        }

        @Override
        public TransferFunctionInputPlayerNearby createNew(TransferFunctionHost host) {
          TransferFunctionInputPlayerNearby function =
              new TransferFunctionInputPlayerNearby(DEFAULT_RADIUS);
          function.updateSource(host);
          return function;
        }

        @Override
        public TransferFunctionInputPlayerNearby load(
            TransferFunctionHost host, ConfigurationNode config) {
          TransferFunctionInputPlayerNearby function =
              new TransferFunctionInputPlayerNearby(config.getOrDefault("radius", DEFAULT_RADIUS));
          function.updateSource(host);
          return function;
        }

        @Override
        public void save(
            TransferFunctionHost host,
            ConfigurationNode config,
            TransferFunctionInputPlayerNearby function) {
          config.set("radius", function.radius);
        }
      };

  private double radius;

  TransferFunctionInputPlayerNearby(double radius) {
    this.radius = clampRadius(radius);
  }

  /** 登记到 TrainCarts 传递函数注册表。须在插件 {@code onLoad} 调用，见类说明。 */
  public static void register() {
    TransferFunction.getRegistry().register(SERIALIZER);
  }

  /** 撤销登记；此后新读入的模型把本输入当作 Unknown。插件停用时调用。 */
  public static void unregister() {
    TransferFunction.getRegistry().unregister(SERIALIZER);
  }

  double radius() {
    return radius;
  }

  @Override
  public Serializer<? extends TransferFunction> getSerializer() {
    return SERIALIZER;
  }

  @Override
  public ReferencedSource createSource(TransferFunctionHost host) {
    Attachment attachment = host.getAttachment();
    return attachment == null ? ReferencedSource.NONE : new NearbySource(attachment, radius);
  }

  @Override
  protected TransferFunctionInput cloneInput() {
    return new TransferFunctionInputPlayerNearby(radius);
  }

  @Override
  public boolean isBooleanOutput() {
    return true;
  }

  @Override
  public void drawPreview(MapWidgetTransferFunctionItem widget, MapCanvas view) {
    view.draw(
        MapFont.MINECRAFT,
        0,
        3,
        MapColorPalette.COLOR_GREEN,
        "<Nearby " + BigDecimal.valueOf(radius).stripTrailingZeros().toPlainString() + "m>");
  }

  @Override
  public void openDialog(Dialog dialog) {
    super.openDialog(dialog);

    dialog.addLabel(11, 20, MapColorPalette.COLOR_RED, "Radius (blocks)");
    dialog
        .addWidget(
            new MapWidgetNumberBox() {
              @Override
              public void onAttached() {
                setRange(MIN_RADIUS, MAX_RADIUS);
                setIncrement(1.0);
                setInitialValue(radius);
                super.onAttached();
              }

              @Override
              public void onValueChanged() {
                radius = clampRadius(getValue());
                updateSource(dialog.getHost());
                dialog.markChanged();
              }

              @Override
              public void onResetValue() {
                setValue(DEFAULT_RADIUS);
              }
            })
        .setBounds(11, 27, dialog.getWidth() - 22, 13);
  }

  private static double clampRadius(double radius) {
    return Double.isNaN(radius)
        ? DEFAULT_RADIUS
        : Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
  }

  /**
   * 由 Sequencer 每 tick 在主线程调用，每 {@link #REFRESH_TICKS} tick 重判一次：附件观看者里有没有人在半径内或坐在这列车上。
   *
   * <p>同一 Sequencer 里半径相同的多处引用共用一个实例（TrainCarts 按 {@link #equals} 去重）。
   */
  static final class NearbySource extends ReferencedSource {
    private final Attachment attachment;
    private final double radius;
    private final Predicate<Player> ridesTrain;
    private int refreshCountdown;

    NearbySource(Attachment attachment, double radius) {
      this(attachment, radius, player -> TrainViewers.ridesTrainOf(player, attachment));
    }

    NearbySource(Attachment attachment, double radius, Predicate<Player> ridesTrain) {
      this.attachment = attachment;
      this.radius = radius;
      this.ridesTrain = ridesTrain;
    }

    @Override
    public void onTick() {
      if (--refreshCountdown > 0) {
        return;
      }
      refreshCountdown = REFRESH_TICKS;
      double reach = value != 0.0 ? radius + EXIT_MARGIN : radius;
      value = anyViewerNearby(reach) ? 1.0 : 0.0;
    }

    private boolean anyViewerNearby(double reach) {
      Collection<Player> viewers = attachment.getViewers();
      if (viewers.isEmpty()) {
        return false;
      }
      Matrix4x4 transform = attachment.getTransform();
      return TrainViewers.anyWithin(
              viewers,
              attachment.getManager().getWorld(),
              transform.m03,
              transform.m13,
              transform.m23,
              reach)
          || viewers.stream().anyMatch(ridesTrain);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof NearbySource other
          && other.attachment == attachment
          && Double.compare(other.radius, radius) == 0;
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(attachment) + Double.hashCode(radius);
    }
  }
}
