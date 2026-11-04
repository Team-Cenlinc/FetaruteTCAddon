package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.common.wrappers.BlockData;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 只画给一名岗位玩家看的标记：该站哪里（横跨轨道的发光标线，与驾驶员的停车标同一画法）、要办的是哪列车（每节车厢罩一个发光的半透明罩子，一闪一闪）。
 *
 * <p>全部走数据包（{@link ClientBlockDisplay}），服务器上没有实体，别的玩家看不到。TrainCarts
 * 自带的附件发光对所有观看者都生效，做不到只给一个人看，所以另画。只在主线程使用。
 */
public final class CrewMarks {

  /** 一节车厢：位置（车厢底部中心）、朝向、车体长度（格）。 */
  public record Car(Vector position, Vector forward, double lengthBlocks) {}

  /** 最多罩几节：再长的列车，前后几节也足够认出来。 */
  private static final int MAX_CARS = 16;

  private static final float LINE_WIDTH = 3.0f;
  private static final float LINE_HEIGHT = 0.1f;
  private static final float LINE_DEPTH = 0.3f;
  private static final double LINE_LIFT = 0.02;

  /** 车厢罩子的宽与高（格）。 */
  private static final float CAR_WIDTH = 3.0f;

  private static final float CAR_HEIGHT = 3.6f;

  /** 量不出车体长度时按这么长画（格）。 */
  private static final double DEFAULT_CAR_LENGTH = 8.0;

  /** 渲染距离倍率：1 为 64 格。 */
  private static final float VIEW_RANGE = 3.0f;

  /** 车厢罩子移动时客户端插值的 tick 数：与刷新周期相同，跟车才顺。 */
  private static final int CAR_MOVE_TICKS = 5;

  private final ClientBlockDisplay line =
      new ClientBlockDisplay(new Vector(LINE_WIDTH, LINE_HEIGHT, LINE_DEPTH), VIEW_RANGE, 2);
  private final List<ClientBlockDisplay> cars = new ArrayList<>();
  private final List<Double> carLengths = new ArrayList<>();

  public CrewMarks() {
    line.setAppearance(BlockData.fromMaterial(Material.LIGHT_BLUE_CONCRETE), Color.AQUA.asRGB());
  }

  /** 显示或挪动站位标线：{@code position} 是停稳后车头的位置，{@code forward} 是轨道走向。 */
  public void showStand(Player player, World world, Vector position, Vector forward) {
    line.setForward(forward);
    line.sync(player, world, position.clone().add(new Vector(0.0, LINE_LIFT, 0.0)));
  }

  /** 撤掉站位标线。玩家已离线时为 {@code null}。 */
  public void hideStand(Player player) {
    line.destroy(player);
  }

  /** 这一拍把这几节车厢罩上。 */
  public void showCars(Player player, World world, List<Car> shown) {
    int count = Math.min(MAX_CARS, shown.size());
    for (int i = 0; i < count; i++) {
      Car car = shown.get(i);
      double length = car.lengthBlocks() > 0.0 ? car.lengthBlocks() : DEFAULT_CAR_LENGTH;
      ClientBlockDisplay display = carDisplay(player, i, length);
      display.setForward(car.forward());
      display.sync(player, world, car.position());
    }
    for (int i = count; i < cars.size(); i++) {
      cars.get(i).destroy(player);
    }
  }

  /** 撤掉车厢罩子。玩家已离线时为 {@code null}。 */
  public void hideCars(Player player) {
    cars.forEach(display -> display.destroy(player));
  }

  /** 全部撤掉。玩家已离线时为 {@code null}。 */
  public void hide(Player player) {
    hideStand(player);
    hideCars(player);
  }

  /** 第 {@code index} 节的罩子；车体长度变了（换了一列车）时换一个，缩放只在生成时定。 */
  private ClientBlockDisplay carDisplay(Player player, int index, double length) {
    if (index < cars.size() && Math.abs(carLengths.get(index) - length) < 0.01) {
      return cars.get(index);
    }
    ClientBlockDisplay display =
        new ClientBlockDisplay(
            new Vector(CAR_WIDTH, CAR_HEIGHT, (float) length), VIEW_RANGE, CAR_MOVE_TICKS);
    display.setAppearance(
        BlockData.fromMaterial(Material.LIGHT_BLUE_STAINED_GLASS), Color.AQUA.asRGB());
    if (index < cars.size()) {
      cars.get(index).destroy(player);
      cars.set(index, display);
      carLengths.set(index, length);
    } else {
      cars.add(display);
      carLengths.add(length);
    }
    return display;
  }
}
