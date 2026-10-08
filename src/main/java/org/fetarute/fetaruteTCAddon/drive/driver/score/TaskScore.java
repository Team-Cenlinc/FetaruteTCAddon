package org.fetarute.fetaruteTCAddon.drive.driver.score;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 一趟任务的成绩明细：各站停站、介入与确认的计数、晚点变化。只在服务器主线程写入。
 *
 * <p>晚点按“结束时的晚点 − 接班时的晚点”计，调度扣车造成的晚点同样会摊进来；扣分时先让出一段余量。
 */
public final class TaskScore {

  private final List<StopScore> stops = new ArrayList<>();
  private int serviceInterventions;
  private int emergencyInterventions;
  private int forcedStops;
  private int signalAcknowledgements;
  private int signalMisses;
  private double signalReactionSeconds;
  private int vigilanceTrips;
  private int lateDepartureConfirmations;
  private OptionalLong delayAtStartSeconds = OptionalLong.empty();
  private OptionalLong delayAtEndSeconds = OptionalLong.empty();
  private double manualBlocks;
  private double atoBlocks;
  private int atoStops;

  public void addStop(StopScore stop) {
    if (stop != null) {
      stops.add(stop);
    }
  }

  public List<StopScore> stops() {
    return List.copyOf(stops);
  }

  /** 已记下成绩的停站数。 */
  public int stopCount() {
    return stops.size();
  }

  /** 记下走过的距离（格），按当时是人工驾驶还是 ATO 分开计。 */
  public void addDistance(double blocks, boolean ato) {
    if (!(blocks > 0.0) || !Double.isFinite(blocks)) {
      return;
    }
    if (ato) {
      atoBlocks += blocks;
    } else {
      manualBlocks += blocks;
    }
  }

  /** 人工驾驶走过的距离（格）。 */
  public double manualBlocks() {
    return manualBlocks;
  }

  /** ATO 运行走过的距离（格）。 */
  public double atoBlocks() {
    return atoBlocks;
  }

  /** ATO 下停了一站（站台由系统停，不计对标成绩，只计站数）。 */
  public void addAtoStop() {
    atoStops++;
  }

  /** ATO 下停的站数。 */
  public int atoStops() {
    return atoStops;
  }

  /** 记下防护介入与确认的计数（结束时一次写入）。 */
  public void setCounts(
      int service,
      int emergency,
      int forced,
      int acknowledgements,
      int misses,
      double reactionSeconds,
      int vigilance,
      int lateDepartures) {
    this.serviceInterventions = service;
    this.emergencyInterventions = emergency;
    this.forcedStops = forced;
    this.signalAcknowledgements = acknowledgements;
    this.signalMisses = misses;
    this.signalReactionSeconds = reactionSeconds;
    this.vigilanceTrips = vigilance;
    this.lateDepartureConfirmations = lateDepartures;
  }

  public void setDelayAtStart(OptionalLong seconds) {
    this.delayAtStartSeconds = seconds == null ? OptionalLong.empty() : seconds;
  }

  /** 是否已记下接班时的晚点。 */
  public boolean hasDelayAtStart() {
    return delayAtStartSeconds.isPresent();
  }

  public void setDelayAtEnd(OptionalLong seconds) {
    this.delayAtEndSeconds = seconds == null ? OptionalLong.empty() : seconds;
  }

  /** 接班时的晚点（秒，提前为负）；查不到时为空。 */
  public OptionalLong delayAtStartSeconds() {
    return delayAtStartSeconds;
  }

  /** 结束时的晚点（秒，提前为负）；查不到时为空。 */
  public OptionalLong delayAtEndSeconds() {
    return delayAtEndSeconds;
  }

  /** 驾驶期间晚点增加了多少秒（提前为负）；接班或结束时查不到晚点时为空。 */
  public OptionalLong delayGainedSeconds() {
    if (delayAtStartSeconds.isEmpty() || delayAtEndSeconds.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(delayAtEndSeconds.getAsLong() - delayAtStartSeconds.getAsLong());
  }

  public int serviceInterventions() {
    return serviceInterventions;
  }

  public int emergencyInterventions() {
    return emergencyInterventions;
  }

  public int forcedStops() {
    return forcedStops;
  }

  public int signalAcknowledgements() {
    return signalAcknowledgements;
  }

  public int signalMisses() {
    return signalMisses;
  }

  public double signalReactionSeconds() {
    return signalReactionSeconds;
  }

  public int vigilanceTrips() {
    return vigilanceTrips;
  }

  public int lateDepartureConfirmations() {
    return lateDepartureConfirmations;
  }
}
