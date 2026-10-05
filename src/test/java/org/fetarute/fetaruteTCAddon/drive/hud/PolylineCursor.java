package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.List;
import org.bukkit.util.Vector;

/** 沿折线走的假游标：折线终点是轨道尽头，可以设定走过哪里时前方区块没加载。 */
final class PolylineCursor implements TrackProfile.Cursor {

  private final List<Vector> points;
  private final double length;
  private double at;

  /** 走过这里（格）就报前方区块没加载。 */
  double blockedFrom = Double.POSITIVE_INFINITY;

  /** 累计走了多少格、前进了几次。 */
  double advanced;

  int advances;

  /** 每次前进前调用（用例拿它推动假时钟）。 */
  Runnable onAdvance = () -> {};

  PolylineCursor(List<Vector> points) {
    this.points = List.copyOf(points);
    double total = 0.0;
    for (int i = 1; i < points.size(); i++) {
      total += points.get(i).distance(points.get(i - 1));
    }
    this.length = total;
  }

  /**
   * 从 {@code start} 起沿 {@code direction}（水平）走 {@code straight} 格后，转向 {@code turned} 再走 {@code
   * after} 格。
   */
  static PolylineCursor bent(
      Vector start, Vector direction, double straight, Vector turned, double after) {
    Vector unit = direction.clone().normalize();
    Vector bend = start.clone().add(unit.multiply(straight));
    Vector end = bend.clone().add(turned.clone().normalize().multiply(after));
    return new PolylineCursor(List.of(start.clone(), bend, end));
  }

  @Override
  public Vector position() {
    double remaining = at;
    for (int i = 1; i < points.size(); i++) {
      Vector a = points.get(i - 1);
      Vector b = points.get(i);
      double segment = a.distance(b);
      if (remaining <= segment || i == points.size() - 1) {
        return a.clone().add(b.clone().subtract(a).multiply(remaining / segment));
      }
      remaining -= segment;
    }
    return points.get(0).clone();
  }

  @Override
  public Vector direction() {
    double remaining = at;
    for (int i = 1; i < points.size(); i++) {
      Vector a = points.get(i - 1);
      Vector b = points.get(i);
      double segment = a.distance(b);
      if (remaining < segment || i == points.size() - 1) {
        return b.clone().subtract(a).normalize();
      }
      remaining -= segment;
    }
    return new Vector(1.0, 0.0, 0.0);
  }

  @Override
  public TrackProfile.Advance advance(double blocks) {
    advances++;
    onAdvance.run();
    if (at + blocks > blockedFrom + 1.0e-9) {
      return new TrackProfile.Advance(TrackProfile.Status.BLOCKED, 0.0);
    }
    double moved = Math.min(blocks, length - at);
    at += moved;
    advanced += moved;
    return new TrackProfile.Advance(
        moved + 1.0e-9 < blocks ? TrackProfile.Status.ENDED : TrackProfile.Status.MOVED, moved);
  }
}
