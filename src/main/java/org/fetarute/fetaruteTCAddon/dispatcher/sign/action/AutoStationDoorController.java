package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationNode;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * AutoStation 的开关门动画控制器。
 *
 * <p>约定：标准动画名称为 {@code doorL/doorR}；旧车使用 {@code doorL10/doorR10}。
 */
public final class AutoStationDoorController {

  private static final String DOOR_LEFT = "doorL";
  private static final String DOOR_RIGHT = "doorR";
  private static final String DOOR_LEFT_LEGACY = "doorL10";
  private static final String DOOR_RIGHT_LEGACY = "doorR10";
  private static final String CHIME_GENERIC = "doorChime";
  private static final String CHIME_OPEN = "doorOpenChime";
  private static final String CHIME_CLOSE = "doorCloseChime";
  private static final double DOOR_SOUND_RADIUS_BLOCKS = 12.0;
  private static final double LEGACY_SPLIT_SECONDS = 5.0;
  private static final int CHIME_REPEAT_COUNT = 3;
  private static final long CHIME_REPEAT_INTERVAL_TICKS = 10L;
  private static final long LEGACY_CLOSE_SOUND_DELAY_TICKS = 80L;
  private static final int MAX_WORLD_SELECT_ANCHOR_DEPTH = 6;
  private static final double WORLD_SELECT_PROBE_OFFSET_BLOCKS = 0.75;
  private static final Set<String> LEFT_ANIMATIONS =
      Set.of(DOOR_LEFT.toLowerCase(Locale.ROOT), DOOR_LEFT_LEGACY.toLowerCase(Locale.ROOT));
  private static final Set<String> RIGHT_ANIMATIONS =
      Set.of(DOOR_RIGHT.toLowerCase(Locale.ROOT), DOOR_RIGHT_LEGACY.toLowerCase(Locale.ROOT));

  private AutoStationDoorController() {}

  /**
   * worldSelect 探针：用于处理“pivot/parent 坐标完全相同但旋转不同”的门模型。
   *
   * <p>当门附件通过 yaw 180° 镜像到车体另一侧时，仅比较 pivot 的 X/Z 可能永远 tie；此时可取 attachment
   * 的世界变换（含旋转）并采样一个局部偏移点，把旋转带来的位移差异纳入判定。
   */
  private enum WorldSelectProbe {
    PIVOT("pivot", 0.0, 0.0),
    LOCAL_X("localX", WORLD_SELECT_PROBE_OFFSET_BLOCKS, 0.0),
    LOCAL_NEG_X("localNegX", -WORLD_SELECT_PROBE_OFFSET_BLOCKS, 0.0),
    LOCAL_Z("localZ", 0.0, WORLD_SELECT_PROBE_OFFSET_BLOCKS),
    LOCAL_NEG_Z("localNegZ", 0.0, -WORLD_SELECT_PROBE_OFFSET_BLOCKS);

    private final String label;
    private final double localX;
    private final double localZ;

    WorldSelectProbe(String label, double localX, double localZ) {
      this.label = label;
      this.localX = localX;
      this.localZ = localZ;
    }

    static List<WorldSelectProbe> candidates() {
      return List.of(LOCAL_X, LOCAL_NEG_X, LOCAL_Z, LOCAL_NEG_Z);
    }

    String label() {
      return label;
    }

    Vector localPoint() {
      return new Vector(localX, 0.0, localZ);
    }
  }

  /**
   * 根据列车朝向 + 牌子方向生成开关门计划。
   *
   * <p>BOTH 直接双侧开门；N/E/S/W 优先按门附件在世界中的位置判断开门侧（不随折返翻转），无法判别时回退到基于车头朝向的 leftOf/rightOf 推导。
   */
  static DoorSession plan(
      MinecartGroup group,
      BlockFace facingDirection,
      AutoStationDoorDirection doorDirection,
      DoorChimeSettings chimeSettings) {
    DoorChimeSettings resolved = chimeSettings == null ? DoorChimeSettings.none() : chimeSettings;
    if (group == null || doorDirection == null || doorDirection == AutoStationDoorDirection.NONE) {
      return DoorSession.empty(resolved, "door=none");
    }
    if (doorDirection == AutoStationDoorDirection.BOTH) {
      return new DoorSession(group, true, true, resolved, "door=both");
    }
    Optional<BlockFace> desiredOpt = doorDirection.toBlockFace();
    if (desiredOpt.isEmpty()) {
      return DoorSession.empty(resolved, "door=" + doorDirection + ",desired=unknown");
    }
    BlockFace desired = desiredOpt.get();
    DoorSideDecision decision = chooseDoorSideByWorldDecision(group, desired);
    if (decision.selection() == null) {
      if (isCardinal(facingDirection)) {
        BlockFace left = leftOf(facingDirection);
        BlockFace right = rightOf(facingDirection);
        boolean openLeft = desired == left;
        boolean openRight = desired == right;
        if (openLeft || openRight) {
          return new DoorSession(
              group,
              openLeft,
              openRight,
              resolved,
              decision.summary() + ",fallback=facing(" + facingDirection + ")");
        }
      }
      return DoorSession.empty(resolved, decision.summary());
    }
    DoorSideSelection selection = decision.selection();
    return new DoorSession(
        group, selection.openLeft, selection.openRight, resolved, decision.summary());
  }

  /**
   * 开门侧选择结果：openLeft/openRight 对应 doorL/doorR（相对列车定义）。
   *
   * <p>注意：这里的 left/right 不依赖列车行进方向推导；left/right 的“世界侧归属”由附件位置决定。
   */
  private static final class DoorSideSelection {
    private final boolean openLeft;
    private final boolean openRight;

    private DoorSideSelection(boolean openLeft, boolean openRight) {
      this.openLeft = openLeft;
      this.openRight = openRight;
    }
  }

  private record DoorSideDecision(DoorSideSelection selection, String summary) {}

  /**
   * 使用门附件在世界坐标中的位置决定应打开 doorL 还是 doorR。
   *
   * <p>AutoStation 第 4 行 N/E/S/W 为世界绝对方向：不应因列车折返/倒车导致左右门翻转。
   *
   * <p>实现要点：
   *
   * <ul>
   *   <li>只比较“doorL 与 doorR 各取一个代表门”的一对（同一门对的两侧），避免把多个车厢/多个门位混在一起导致 tie；
   *   <li>候选门的选择优先避开同时声明 doorL 与 doorR 的附件（这类节点更像“动画容器”，通常没有区分左右的空间信息）。
   * </ul>
   *
   * <p>当门附件尚未 attach 或无法计算位置时返回 null，让上层继续等待/重试。
   */
  private static DoorSideDecision chooseDoorSideByWorldDecision(
      MinecartGroup group, BlockFace desired) {
    if (group == null || desired == null || !isCardinal(desired)) {
      return new DoorSideDecision(null, "worldSelect=invalidInput(desired=" + desired + ")");
    }

    World world = null;
    Location trainFallback = null;
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      world = member.getWorld();
      if (world != null) {
        trainFallback = member.getBlock(0, 0, 0).getLocation();
        break;
      }
    }
    if (world == null) {
      return new DoorSideDecision(null, "worldSelect=noWorld(desired=" + desired + ")");
    }

    Collection<String> animationNames = group.getAnimationNames();
    String leftName = findAnimationName(animationNames, DOOR_LEFT);
    leftName = leftName != null ? leftName : findAnimationName(animationNames, DOOR_LEFT_LEGACY);
    String rightName = findAnimationName(animationNames, DOOR_RIGHT);
    rightName =
        rightName != null ? rightName : findAnimationName(animationNames, DOOR_RIGHT_LEGACY);

    List<Attachment> leftTargets =
        leftName == null ? List.of() : findAnimationTargets(group, leftName);
    List<Attachment> rightTargets =
        rightName == null ? List.of() : findAnimationTargets(group, rightName);
    int leftAttached = countAttachedTargets(leftTargets);
    int rightAttached = countAttachedTargets(rightTargets);

    int overlap = countOverlapTargets(leftTargets, rightTargets);
    TargetCandidates candidates =
        buildCandidates(leftName, rightName, leftTargets, rightTargets, overlap);
    int leftCandidateAttached = countAttachedTargets(candidates.leftTargets());
    int rightCandidateAttached = countAttachedTargets(candidates.rightTargets());

    double eps = 0.02;
    String anchorTry = "";
    String probeTry = "";
    WorldSelectAttempt attempt =
        computeWorldSelectAttempt(
            world,
            desired,
            candidates.leftTargets(),
            candidates.rightTargets(),
            trainFallback,
            0,
            WorldSelectProbe.PIVOT,
            eps);
    String retry = "";
    int maxAnchorDepth = maxAnchorDepth(leftName, rightName, leftTargets, rightTargets);
    if (maxAnchorDepth > 0) {
      StringBuilder trials = new StringBuilder();
      appendAnchorTrial(trials, 0, attempt);
      WorldSelectAttempt best = attempt;
      int chosenDepth = 0;
      for (int depth = 1; depth <= maxAnchorDepth; depth++) {
        WorldSelectAttempt candidate =
            computeWorldSelectAttempt(
                world,
                desired,
                candidates.leftTargets(),
                candidates.rightTargets(),
                trainFallback,
                depth,
                WorldSelectProbe.PIVOT,
                eps);
        appendAnchorTrial(trials, depth, candidate);
        if (isAttemptBetter(candidate, best, eps)) {
          best = candidate;
          chosenDepth = depth;
        }
      }
      attempt = best;
      if (chosenDepth != 0) {
        retry = "target->" + formatAnchorDepth(chosenDepth);
      }
      boolean tieLike =
          attempt.leftRep() != null
              && attempt.rightRep() != null
              && Double.isFinite(attempt.delta())
              && Math.abs(attempt.delta()) <= eps;
      if (attempt.selection() == null || tieLike || !retry.isBlank()) {
        anchorTry = trials.toString();
      }
    }

    boolean tieLike =
        attempt.leftRep() != null
            && attempt.rightRep() != null
            && Double.isFinite(attempt.delta())
            && Math.abs(attempt.delta()) <= eps;
    if (attempt.selection() == null || tieLike) {
      int maxDepth = maxAnchorDepth;
      StringBuilder trials = new StringBuilder();
      WorldSelectAttempt best = attempt;
      for (int depth = 0; depth <= maxDepth; depth++) {
        for (WorldSelectProbe probe : WorldSelectProbe.candidates()) {
          WorldSelectAttempt candidate =
              computeWorldSelectAttempt(
                  world,
                  desired,
                  candidates.leftTargets(),
                  candidates.rightTargets(),
                  trainFallback,
                  depth,
                  probe,
                  eps);
          appendProbeTrial(trials, candidate);
          if (isAttemptBetter(candidate, best, eps)) {
            best = candidate;
          }
        }
      }
      if (best != attempt) {
        attempt = best;
      }
      if (trials.length() > 0) {
        probeTry = trials.toString();
      }
    }

    String anchor = formatAnchorDepth(attempt.anchorDepth());
    String probe = formatProbe(attempt.probe());
    String probeOffset =
        attempt.probe() == null || attempt.probe() == WorldSelectProbe.PIVOT
            ? ""
            : String.valueOf(WORLD_SELECT_PROBE_OFFSET_BLOCKS);
    LocationPair pair = attempt.pair();
    Location leftRep = attempt.leftRep();
    Location rightRep = attempt.rightRep();
    DoorSideSelection selection = attempt.selection();
    String reason = attempt.reason();
    double scoreL = attempt.scoreL();
    double scoreR = attempt.scoreR();
    double delta = attempt.delta();
    if (selection == null) {
      DoorSideSelection axisSelection =
          axisSideSelection(
              world, desired, candidates.leftTargets(), candidates.rightTargets(), eps);
      if (axisSelection != null) {
        selection = axisSelection;
        reason = reason + "+axis";
      }
    }
    if (selection == null) {
      DoorSideSelection fallback =
          maxSeparationSelection(
              world,
              desired,
              candidates.leftTargets(),
              candidates.rightTargets(),
              attempt.anchorDepth(),
              attempt.probe(),
              eps);
      if (fallback != null) {
        selection = fallback;
        reason = reason + "+maxSeparation";
      }
    }

    if (leftRep == null && rightRep == null) {
      return new DoorSideDecision(
          null,
          "worldSelect=unavailable(desired="
              + desired
              + ",world="
              + world.getName()
              + ",leftAnim="
              + (leftName != null ? leftName : "none")
              + ",rightAnim="
              + (rightName != null ? rightName : "none")
              + ",leftTargets="
              + leftTargets.size()
              + "/"
              + leftAttached
              + ",rightTargets="
              + rightTargets.size()
              + "/"
              + rightAttached
              + ",overlap="
              + overlap
              + ",candL="
              + candidates.leftTargets().size()
              + "/"
              + leftCandidateAttached
              + ",candR="
              + candidates.rightTargets().size()
              + "/"
              + rightCandidateAttached
              + ",anchor="
              + anchor
              + ",probe="
              + probe
              + (probeOffset.isBlank() ? "" : ",probeOffset=" + probeOffset)
              + (anchorTry.isBlank() ? "" : ",anchorTry=" + anchorTry)
              + (probeTry.isBlank() ? "" : ",probeTry=" + probeTry)
              + (retry.isBlank() ? "" : ",retry=" + retry)
              + ")");
    }

    String summary =
        "worldSelect(desired="
            + desired
            + ",world="
            + world.getName()
            + ",leftAnim="
            + (leftName != null ? leftName : "none")
            + ",rightAnim="
            + (rightName != null ? rightName : "none")
            + ",leftTargets="
            + leftTargets.size()
            + "/"
            + leftAttached
            + ",rightTargets="
            + rightTargets.size()
            + "/"
            + rightAttached
            + ",overlap="
            + overlap
            + ",candL="
            + candidates.leftTargets().size()
            + "/"
            + leftCandidateAttached
            + ",candR="
            + candidates.rightTargets().size()
            + "/"
            + rightCandidateAttached
            + ",pairLongAxis="
            + pair.longAxis()
            + ",pairLongDelta="
            + formatScore(pair.longDelta())
            + ",pairSideAbs="
            + formatScore(pair.sideAbs())
            + ",anchor="
            + anchor
            + ",probe="
            + probe
            + (probeOffset.isBlank() ? "" : ",probeOffset=" + probeOffset)
            + (anchorTry.isBlank() ? "" : ",anchorTry=" + anchorTry)
            + (probeTry.isBlank() ? "" : ",probeTry=" + probeTry)
            + (retry.isBlank() ? "" : ",retry=" + retry)
            + ",axis="
            + axisName(desired)
            + ",sign="
            + desiredSign(desired)
            + ",repL="
            + formatXZ(leftRep)
            + ",repR="
            + formatXZ(rightRep)
            + ",scoreL="
            + formatScore(scoreL)
            + ",scoreR="
            + formatScore(scoreR)
            + ",delta="
            + formatScore(delta)
            + ",eps="
            + eps
            + ",reason="
            + reason
            + ",chosen="
            + formatChosen(selection)
            + ",fallback="
            + (trainFallback != null ? formatXZ(trainFallback) : "none")
            + ")";
    return new DoorSideDecision(selection, summary);
  }

  private static String formatAnchorDepth(int depth) {
    if (depth <= 0) {
      return "target";
    }
    if (depth == 1) {
      return "parent";
    }
    if (depth == 2) {
      return "grandparent";
    }
    return "ancestor" + depth;
  }

  private static String formatProbe(WorldSelectProbe probe) {
    if (probe == null) {
      return "none";
    }
    return probe.label();
  }

  private static void appendAnchorTrial(
      StringBuilder trials, int depth, WorldSelectAttempt attempt) {
    if (trials == null) {
      return;
    }
    if (trials.length() > 0) {
      trials.append("|");
    }
    trials.append(formatAnchorDepth(depth)).append(":");
    if (attempt == null || !attempt.hasAnyRep()) {
      trials.append("none");
      return;
    }
    double delta = attempt.delta();
    if (!Double.isFinite(delta)) {
      trials.append("nan");
      return;
    }
    trials.append(String.format(Locale.ROOT, "%.3f", delta));
    if (attempt.selection() == null) {
      trials.append("(tie)");
    }
  }

  private static void appendProbeTrial(StringBuilder trials, WorldSelectAttempt attempt) {
    if (trials == null || attempt == null) {
      return;
    }
    if (trials.length() > 0) {
      trials.append("|");
    }
    trials
        .append(formatAnchorDepth(attempt.anchorDepth()))
        .append("/")
        .append(formatProbe(attempt.probe()))
        .append(":");
    if (!attempt.hasAnyRep()) {
      trials.append("none");
      return;
    }
    double delta = attempt.delta();
    if (!Double.isFinite(delta)) {
      trials.append("nan");
      return;
    }
    trials.append(String.format(Locale.ROOT, "%.3f", delta));
    if (attempt.selection() == null) {
      trials.append("(tie)");
    }
  }

  private record WorldSelectAttempt(
      int anchorDepth,
      WorldSelectProbe probe,
      LocationPair pair,
      Location leftRep,
      Location rightRep,
      DoorSideSelection selection,
      String reason,
      double scoreL,
      double scoreR,
      double delta) {
    private boolean hasAnyRep() {
      return leftRep != null || rightRep != null;
    }
  }

  private static WorldSelectAttempt computeWorldSelectAttempt(
      World world,
      BlockFace desired,
      List<Attachment> leftTargets,
      List<Attachment> rightTargets,
      Location trainFallback,
      int anchorDepth,
      WorldSelectProbe probe,
      double eps) {
    WorldSelectProbe resolvedProbe = probe == null ? WorldSelectProbe.PIVOT : probe;
    List<Location> leftLocations =
        collectAttachedLocations(world, leftTargets, anchorDepth, resolvedProbe);
    List<Location> rightLocations =
        collectAttachedLocations(world, rightTargets, anchorDepth, resolvedProbe);
    LocationPair pair =
        pickRepresentativeDoorPairFromLocations(world, desired, leftLocations, rightLocations);
    RepresentativeScore leftScore = representativeScoreByMedian(desired, leftLocations);
    RepresentativeScore rightScore = representativeScoreByMedian(desired, rightLocations);
    Location leftRep = leftScore.location();
    Location rightRep = rightScore.location();

    DoorSideSelection selection = null;
    String reason = "delta";
    double scoreL = leftScore.score();
    double scoreR = rightScore.score();
    double delta = scoreL - scoreR;
    if (leftRep != null && rightRep != null) {
      if (Math.abs(delta) > eps) {
        selection =
            delta > 0.0 ? new DoorSideSelection(true, false) : new DoorSideSelection(false, true);
      } else {
        reason = "tie";
        if (trainFallback != null) {
          double trainScore = desiredScore(desired, trainFallback);
          double leftDeltaToTrain = scoreL - trainScore;
          double rightDeltaToTrain = scoreR - trainScore;
          if (leftDeltaToTrain > eps && rightDeltaToTrain > eps) {
            selection = new DoorSideSelection(true, true);
            reason = "tieSameSide";
          } else if (leftDeltaToTrain < -eps && rightDeltaToTrain < -eps) {
            selection = new DoorSideSelection(false, false);
            reason = "tieOppositeSide";
          }
        }
      }
    } else if (leftRep != null && trainFallback != null) {
      double trainScore = desiredScore(desired, trainFallback);
      double deltaToTrain = scoreL - trainScore;
      if (Math.abs(deltaToTrain) > eps) {
        selection = deltaToTrain > 0.0 ? new DoorSideSelection(true, false) : null;
        reason = "singleLeft";
      } else {
        reason = "singleLeftTie";
      }
    } else if (rightRep != null && trainFallback != null) {
      double trainScore = desiredScore(desired, trainFallback);
      double deltaToTrain = scoreR - trainScore;
      if (Math.abs(deltaToTrain) > eps) {
        selection = deltaToTrain > 0.0 ? new DoorSideSelection(false, true) : null;
        reason = "singleRight";
      } else {
        reason = "singleRightTie";
      }
    } else {
      reason = "singleNoFallback";
    }
    return new WorldSelectAttempt(
        anchorDepth,
        resolvedProbe,
        pair,
        leftRep,
        rightRep,
        selection,
        reason,
        scoreL,
        scoreR,
        delta);
  }

  private static boolean isAttemptBetter(
      WorldSelectAttempt candidate, WorldSelectAttempt baseline, double eps) {
    if (candidate == null || !candidate.hasAnyRep()) {
      return false;
    }
    if (baseline == null || !baseline.hasAnyRep()) {
      return true;
    }
    if (candidate.selection() != null && baseline.selection() == null) {
      return true;
    }
    if (candidate.selection() == null && baseline.selection() != null) {
      return false;
    }
    double candidateAbs = safeAbs(candidate.delta());
    double baselineAbs = safeAbs(baseline.delta());
    if (!Double.isFinite(candidateAbs) && Double.isFinite(baselineAbs)) {
      return false;
    }
    if (Double.isFinite(candidateAbs) && !Double.isFinite(baselineAbs)) {
      return true;
    }
    boolean candidateNonPivot =
        candidate.probe() != null && candidate.probe() != WorldSelectProbe.PIVOT;
    boolean baselinePivot = baseline.probe() == null || baseline.probe() == WorldSelectProbe.PIVOT;
    if (candidateNonPivot && baselinePivot && candidateAbs >= baselineAbs - 1.0e-6) {
      return true;
    }
    if (candidateAbs > baselineAbs + 0.01) {
      return true;
    }
    return baselineAbs <= eps && candidateAbs > baselineAbs + 1.0e-6;
  }

  private static double safeAbs(double value) {
    return Double.isFinite(value) ? Math.abs(value) : Double.NaN;
  }

  private record RepresentativeScore(Location location, double score) {}

  private record ScoredLocation(Location location, double score) {}

  private static RepresentativeScore representativeScoreByMedian(
      BlockFace desired, List<Location> locations) {
    if (desired == null || locations == null || locations.isEmpty()) {
      return new RepresentativeScore(null, Double.NaN);
    }
    List<ScoredLocation> scored = new ArrayList<>();
    for (Location location : locations) {
      if (location == null) {
        continue;
      }
      double score = desiredScore(desired, location);
      if (Double.isFinite(score)) {
        scored.add(new ScoredLocation(location, score));
      }
    }
    if (scored.isEmpty()) {
      return new RepresentativeScore(null, Double.NaN);
    }
    scored.sort((a, b) -> Double.compare(a.score(), b.score()));
    int size = scored.size();
    int midIndex = size / 2;
    double medianScore;
    if (size % 2 == 1) {
      medianScore = scored.get(midIndex).score();
    } else {
      medianScore = (scored.get(midIndex - 1).score() + scored.get(midIndex).score()) / 2.0;
    }
    Location representativeLocation = scored.get(midIndex).location();
    return new RepresentativeScore(representativeLocation, medianScore);
  }

  private static boolean isModernDoorPair(String leftName, String rightName) {
    return leftName != null
        && rightName != null
        && leftName.equalsIgnoreCase(DOOR_LEFT)
        && rightName.equalsIgnoreCase(DOOR_RIGHT);
  }

  private record TargetCandidates(List<Attachment> leftTargets, List<Attachment> rightTargets) {}

  private static TargetCandidates buildCandidates(
      String leftName,
      String rightName,
      List<Attachment> leftTargets,
      List<Attachment> rightTargets,
      int overlap) {
    if (leftTargets == null || rightTargets == null) {
      return new TargetCandidates(List.of(), List.of());
    }
    if (overlap <= 0 || leftName == null || rightName == null) {
      return new TargetCandidates(leftTargets, rightTargets);
    }
    List<Attachment> leftExclusive = filterExclusiveTargets(leftTargets, rightName);
    List<Attachment> rightExclusive = filterExclusiveTargets(rightTargets, leftName);
    List<Attachment> leftCandidates = leftExclusive.isEmpty() ? leftTargets : leftExclusive;
    List<Attachment> rightCandidates = rightExclusive.isEmpty() ? rightTargets : rightExclusive;
    return new TargetCandidates(leftCandidates, rightCandidates);
  }

  private static int maxAnchorDepth(
      String leftName,
      String rightName,
      List<Attachment> leftTargets,
      List<Attachment> rightTargets) {
    if (isModernDoorPair(leftName, rightName)) {
      return MAX_WORLD_SELECT_ANCHOR_DEPTH;
    }
    return hasAnyParent(leftTargets) || hasAnyParent(rightTargets) ? 1 : 0;
  }

  private static boolean hasAnyParent(List<Attachment> targets) {
    if (targets == null || targets.isEmpty()) {
      return false;
    }
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      if (target.getParent() != null) {
        return true;
      }
    }
    return false;
  }

  private static List<Attachment> filterExclusiveTargets(
      List<Attachment> targets, String excludeName) {
    if (targets == null || targets.isEmpty() || excludeName == null || excludeName.isBlank()) {
      return List.of();
    }
    List<Attachment> result = new ArrayList<>();
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      if (!hasAnimation(target, excludeName)) {
        result.add(target);
      }
    }
    return result;
  }

  private record LocationPair(
      Location left, Location right, String longAxis, double longDelta, double sideAbs) {}

  /**
   * 从 doorL/doorR 的位置列表中选取一对“最能体现左右差异”的门对，用于 debug 输出。
   *
   * <p>实现：按 desired 的侧向轴（X/Z）确定“纵向轴”（另一轴），优先最大化侧向差值（越能区分左右越好）；若侧向差值相同，再最小化纵向距离，避免把不同门位匹配到一起。
   */
  private static LocationPair pickRepresentativeDoorPairFromLocations(
      World world, BlockFace desired, List<Location> leftLocations, List<Location> rightLocations) {
    boolean useZ = desired == BlockFace.NORTH || desired == BlockFace.SOUTH;
    String longAxis = useZ ? "X" : "Z";

    if (leftLocations.isEmpty() || rightLocations.isEmpty()) {
      Location left = leftLocations.isEmpty() ? null : leftLocations.get(0);
      Location right = rightLocations.isEmpty() ? null : rightLocations.get(0);
      return new LocationPair(left, right, longAxis, Double.NaN, Double.NaN);
    }

    Location bestLeft = null;
    Location bestRight = null;
    double bestSideAbs = Double.NEGATIVE_INFINITY;
    double bestLongDelta = Double.POSITIVE_INFINITY;
    double bestDistanceSq = Double.POSITIVE_INFINITY;
    for (Location left : leftLocations) {
      for (Location right : rightLocations) {
        double longDelta =
            useZ ? Math.abs(left.getX() - right.getX()) : Math.abs(left.getZ() - right.getZ());
        double sideAbs =
            useZ ? Math.abs(left.getZ() - right.getZ()) : Math.abs(left.getX() - right.getX());
        double distanceSq = left.distanceSquared(right);
        int sideCompare = Double.compare(sideAbs, bestSideAbs);
        if (sideCompare > 0) {
          bestSideAbs = sideAbs;
          bestLongDelta = longDelta;
          bestDistanceSq = distanceSq;
          bestLeft = left;
          bestRight = right;
          continue;
        }
        if (sideCompare < 0) {
          continue;
        }
        int longCompare = Double.compare(longDelta, bestLongDelta);
        if (longCompare < 0 || (longCompare == 0 && distanceSq < bestDistanceSq)) {
          bestSideAbs = sideAbs;
          bestLongDelta = longDelta;
          bestDistanceSq = distanceSq;
          bestLeft = left;
          bestRight = right;
        }
      }
    }
    return new LocationPair(bestLeft, bestRight, longAxis, bestLongDelta, bestSideAbs);
  }

  private static List<Location> collectAttachedLocations(
      World world, List<Attachment> targets, int anchorDepth, WorldSelectProbe probe) {
    if (world == null || targets == null || targets.isEmpty()) {
      return List.of();
    }
    WorldSelectProbe resolvedProbe = probe == null ? WorldSelectProbe.PIVOT : probe;
    List<Location> locations = new ArrayList<>();
    IdentityHashMap<Attachment, Boolean> seen = new IdentityHashMap<>();
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      Attachment anchor = resolveAnchorAttachment(target, anchorDepth);
      if (anchor == null || !anchor.isAttached()) {
        continue;
      }
      if (anchorDepth > 0 && seen.put(anchor, Boolean.TRUE) != null) {
        continue;
      }
      Location location = resolveAnchorLocation(world, anchor, resolvedProbe);
      if (location != null) {
        locations.add(location);
      }
    }
    return locations;
  }

  private static Location resolveAnchorLocation(
      World world, Attachment anchor, WorldSelectProbe probe) {
    if (world == null || anchor == null || probe == null) {
      return null;
    }
    com.bergerkiller.bukkit.common.math.Matrix4x4 transform = anchor.getTransform();
    if (transform == null) {
      return null;
    }
    if (probe == WorldSelectProbe.PIVOT) {
      return transform.toLocation(world);
    }
    Vector local = probe.localPoint();
    transform.transformPoint(local);
    return new Location(world, local.getX(), local.getY(), local.getZ());
  }

  private static Attachment resolveAnchorAttachment(Attachment target, int anchorDepth) {
    if (target == null || anchorDepth <= 0) {
      return target;
    }
    Attachment resolved = target;
    int remaining = anchorDepth;
    while (remaining > 0) {
      Attachment parent = resolved.getParent();
      if (parent == null) {
        break;
      }
      resolved = parent;
      remaining--;
    }
    return resolved;
  }

  private static int countOverlapTargets(
      List<Attachment> leftTargets, List<Attachment> rightTargets) {
    if (leftTargets == null
        || leftTargets.isEmpty()
        || rightTargets == null
        || rightTargets.isEmpty()) {
      return 0;
    }
    int overlap = 0;
    for (Attachment left : leftTargets) {
      if (left == null) {
        continue;
      }
      for (Attachment right : rightTargets) {
        if (left == right) {
          overlap++;
          break;
        }
      }
    }
    return overlap;
  }

  private static String formatChosen(DoorSideSelection selection) {
    if (selection == null) {
      return "none";
    }
    if (selection.openLeft && selection.openRight) {
      return "both";
    }
    if (selection.openLeft) {
      return "left";
    }
    if (selection.openRight) {
      return "right";
    }
    return "none";
  }

  private static String formatScore(double value) {
    if (!Double.isFinite(value)) {
      return "nan";
    }
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static String formatXZ(Location location) {
    if (location == null) {
      return "none";
    }
    return String.format(Locale.ROOT, "(%.2f,%.2f)", location.getX(), location.getZ());
  }

  private static String axisName(BlockFace desired) {
    if (desired == BlockFace.NORTH || desired == BlockFace.SOUTH) {
      return "Z";
    }
    if (desired == BlockFace.EAST || desired == BlockFace.WEST) {
      return "X";
    }
    return "?";
  }

  private static double desiredSign(BlockFace desired) {
    if (desired == BlockFace.SOUTH || desired == BlockFace.EAST) {
      return 1.0;
    }
    if (desired == BlockFace.NORTH || desired == BlockFace.WEST) {
      return -1.0;
    }
    return 0.0;
  }

  private static double desiredScore(BlockFace desired, Location location) {
    if (location == null || desired == null) {
      return Double.NaN;
    }
    double sign = desiredSign(desired);
    if (desired == BlockFace.NORTH || desired == BlockFace.SOUTH) {
      return sign * location.getZ();
    }
    if (desired == BlockFace.EAST || desired == BlockFace.WEST) {
      return sign * location.getX();
    }
    return Double.NaN;
  }

  /** 判断方位是否为水平四向（N/E/S/W）。 */
  static boolean isCardinal(BlockFace face) {
    return face == BlockFace.NORTH
        || face == BlockFace.SOUTH
        || face == BlockFace.EAST
        || face == BlockFace.WEST;
  }

  /** 返回相对于列车行进方向的左侧方位。 */
  static BlockFace leftOf(BlockFace face) {
    if (face == BlockFace.NORTH) {
      return BlockFace.WEST;
    }
    if (face == BlockFace.SOUTH) {
      return BlockFace.EAST;
    }
    if (face == BlockFace.EAST) {
      return BlockFace.NORTH;
    }
    if (face == BlockFace.WEST) {
      return BlockFace.SOUTH;
    }
    return face;
  }

  /** 返回相对于列车行进方向的右侧方位。 */
  static BlockFace rightOf(BlockFace face) {
    if (face == BlockFace.NORTH) {
      return BlockFace.EAST;
    }
    if (face == BlockFace.SOUTH) {
      return BlockFace.WEST;
    }
    if (face == BlockFace.EAST) {
      return BlockFace.SOUTH;
    }
    if (face == BlockFace.WEST) {
      return BlockFace.NORTH;
    }
    return face;
  }

  /** 一次开关门会话，包含左右侧各自的动作策略。 */
  static final class DoorSession {
    private final MinecartGroup group;
    private final boolean openLeft;
    private final boolean openRight;
    private final DoorAction leftAction;
    private final DoorAction rightAction;
    private final DoorChimeSettings chimeSettings;
    private final String selectionSummary;
    private boolean openSucceeded;

    private DoorSession(
        MinecartGroup group,
        boolean openLeft,
        boolean openRight,
        DoorChimeSettings chimeSettings,
        String selectionSummary) {
      this.group = group;
      this.openLeft = openLeft;
      this.openRight = openRight;
      this.chimeSettings = chimeSettings == null ? DoorChimeSettings.none() : chimeSettings;
      this.selectionSummary = selectionSummary != null ? selectionSummary : "";
      this.leftAction =
          openLeft ? buildAction(group, DOOR_LEFT, DOOR_LEFT_LEGACY) : DoorAction.none();
      this.rightAction =
          openRight ? buildAction(group, DOOR_RIGHT, DOOR_RIGHT_LEGACY) : DoorAction.none();
    }

    /** 返回空会话（无开关门动作）。 */
    static DoorSession empty(DoorChimeSettings settings, String reason) {
      return new DoorSession(null, false, false, settings, reason);
    }

    /** 是否存在任一侧的开关门动作。 */
    boolean hasActions() {
      return openLeft || openRight;
    }

    String debugSummary() {
      String leftSummary = describeAction(leftAction, openLeft);
      String rightSummary = describeAction(rightAction, openRight);
      String select =
          selectionSummary == null || selectionSummary.isBlank() ? "-" : selectionSummary;
      return "select=" + select + ", left=" + leftSummary + ", right=" + rightSummary;
    }

    /**
     * 执行开门动作。
     *
     * @return 若至少一侧成功触发动画则返回 true
     */
    boolean open() {
      if (group == null) {
        return false;
      }
      boolean opened = false;
      if (openLeft) {
        opened |= leftAction.open(group);
      }
      if (openRight) {
        opened |= rightAction.open(group);
      }
      if (opened) {
        openSucceeded = true;
        playOpenChime(group, openLeft, openRight, chimeSettings);
      }
      return opened;
    }

    /**
     * 执行关门动作（通常为反向播放/重置）。
     *
     * @return 若至少一侧成功触发动画则返回 true
     */
    boolean close() {
      return close(true);
    }

    /**
     * 执行关门动作，可选择是否播放提示音。
     *
     * <p>用于 legacy 场景：可先触发关门动画，再在合适时机播放提示音。
     */
    boolean close(boolean playSound) {
      boolean started = startCloseAnimation();
      if (started && playSound) {
        playCloseSound();
      }
      return started;
    }

    /** 提前触发关门动画（不播放提示音）。 */
    boolean prepareClose() {
      return startCloseAnimation();
    }

    /** 单独播放关门提示音（不触发关门动画）。 */
    void playCloseChime() {
      playCloseSound();
    }

    /** 是否使用 legacy 门动画（doorL10/doorR10 或其他 legacy 片段）。 */
    boolean usesLegacyDoorAnimation() {
      if (openLeft && leftAction instanceof DoorAction.Legacy) {
        return true;
      }
      return openRight && rightAction instanceof DoorAction.Legacy;
    }

    /** 是否曾成功触发开门动画。 */
    boolean openSucceeded() {
      return openSucceeded;
    }

    /** 估算关门动画时长（tick），仅 legacy 可用。 */
    long estimatedCloseDurationTicks() {
      long left = leftAction.closeDurationTicks();
      long right = rightAction.closeDurationTicks();
      long max = Math.max(left, right);
      return max > 0L ? max : -1L;
    }

    /** 触发关门动画（不播放提示音）。 */
    boolean startCloseAnimation() {
      if (group == null || !openSucceeded) {
        return false;
      }
      boolean closed = false;
      if (openLeft) {
        closed |= leftAction.close(group);
      }
      if (openRight) {
        closed |= rightAction.close(group);
      }
      return closed;
    }

    /** 播放关门提示音（不触发关门动画）。 */
    void playCloseSound() {
      if (group == null || !openSucceeded) {
        return;
      }
      AutoStationDoorController.playCloseSound(group, openLeft, openRight, chimeSettings);
    }
  }

  /** legacy 关门提示音延迟量（tick）。 */
  static long legacyCloseSoundDelayTicks() {
    return Math.max(0L, LEGACY_CLOSE_SOUND_DELAY_TICKS);
  }

  /**
   * 尝试预热门动画与附件树。
   *
   * <p>用于列车刚生成时确保附件变换就绪并重置门动画到起点。
   */
  public static void warmUpDoorAnimations(MinecartGroup group) {
    warmUpDoorAnimations(group, false);
  }

  public static void warmUpDoorAnimations(MinecartGroup group, boolean probePlay) {
    if (group == null) {
      return;
    }
    for (MinecartMember<?> member : group) {
      if (member == null || member.getAttachments() == null) {
        continue;
      }
      if (!member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root != null) {
        root.getTransform();
        root.getChildren();
      }
    }

    Collection<String> animationNames = group.getAnimationNames();
    if (animationNames == null || animationNames.isEmpty()) {
      return;
    }
    warmUpAnimation(group, animationNames, DOOR_LEFT, probePlay);
    warmUpAnimation(group, animationNames, DOOR_RIGHT, probePlay);
    warmUpAnimation(group, animationNames, DOOR_LEFT_LEGACY, probePlay);
    warmUpAnimation(group, animationNames, DOOR_RIGHT_LEGACY, probePlay);
  }

  private static void warmUpAnimation(
      MinecartGroup group, Collection<String> animationNames, String key, boolean probePlay) {
    if (group == null || animationNames == null || key == null) {
      return;
    }
    String name = findAnimationName(animationNames, key);
    if (name == null) {
      return;
    }
    List<Attachment> targets = findAnimationTargets(group, name);
    if (!hasAttachedTargets(targets)) {
      return;
    }
    warmUpAnchors(targets);
    AnimationOptions options = new AnimationOptions(name);
    options.setReset(true);
    options.setSpeed(0.0);
    group.playNamedAnimation(options);
    if (probePlay) {
      AnimationOptions playOptions = new AnimationOptions(name);
      playOptions.setReset(true);
      playOptions.setSpeed(-1.0);
      group.playNamedAnimation(playOptions);
      TrainCarts trainCarts = TrainCarts.plugin;
      if (trainCarts != null) {
        Bukkit.getScheduler()
            .runTaskLater(
                trainCarts,
                () -> {
                  AnimationOptions reset = new AnimationOptions(name);
                  reset.setReset(true);
                  reset.setSpeed(0.0);
                  group.playNamedAnimation(reset);
                },
                2L);
      }
    }
  }

  private static void warmUpAnchors(List<Attachment> targets) {
    if (targets == null || targets.isEmpty()) {
      return;
    }
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      Attachment anchor = resolveAnchorAttachment(target, 1);
      if (anchor == null || !anchor.isAttached()) {
        continue;
      }
      anchor.getTransform();
    }
  }

  /**
   * 构建门动画动作。
   *
   * <p>优先 doorL/doorR，缺失时尝试解析 doorL10/doorR10 的“开门片段”，再不行就直接播放 legacy 动画。
   */
  private static DoorAction buildAction(
      MinecartGroup group, String primaryName, String legacyName) {
    if (group == null) {
      return DoorAction.none();
    }
    Collection<String> names = group.getAnimationNames();
    String primary = findAnimationName(names, primaryName);
    if (primary != null) {
      List<Attachment> targets = findAnimationTargets(group, primary);
      return DoorAction.named(primary, targets);
    }
    String legacy = findAnimationName(names, legacyName);
    if (legacy == null) {
      return DoorAction.none();
    }
    List<Attachment> legacyTargets = findAnimationTargets(group, legacy);
    if (legacyTargets.isEmpty()) {
      return DoorAction.none();
    }
    return buildLegacyAction(group, legacy, legacyTargets);
  }

  /** 在动画名列表中做不区分大小写匹配。 */
  private static String findAnimationName(Collection<String> names, String target) {
    if (names == null || target == null) {
      return null;
    }
    for (String name : names) {
      if (name != null && name.equalsIgnoreCase(target)) {
        return name;
      }
    }
    return null;
  }

  /**
   * 遍历整列车附件树，收集包含指定动画的附件节点。
   *
   * <p>用于只触发门附件，避免 legacy 动画影响整车。
   */
  private static List<Attachment> findAnimationTargets(MinecartGroup group, String name) {
    if (group == null || name == null || name.isBlank()) {
      return List.of();
    }
    List<Attachment> targets = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      if (member.getAttachments() == null || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root == null) {
        continue;
      }
      collectAnimationTargets(root, name, targets);
    }
    return targets;
  }

  /** 递归扫描附件树，收集含指定动画的附件节点。 */
  private static void collectAnimationTargets(
      Attachment attachment, String name, List<Attachment> targets) {
    if (attachment == null || name == null || targets == null) {
      return;
    }
    if (hasAnimation(attachment, name)) {
      targets.add(attachment);
    }
    for (Attachment child : attachment.getChildren()) {
      collectAnimationTargets(child, name, targets);
    }
  }

  /** 判断附件是否声明了指定动画名（大小写不敏感）。 */
  private static boolean hasAnimation(Attachment attachment, String name) {
    if (attachment == null || name == null) {
      return false;
    }
    Collection<String> names = attachment.getAnimationNames();
    if (names == null || names.isEmpty()) {
      return false;
    }
    for (String entry : names) {
      if (entry == null) {
        continue;
      }
      if (entry.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 从 legacy 动画中截取“开门段”，并构建反向的关门段。
   *
   * <p>截取规则：doorL10/doorR10 会按持续时间切出前 5 秒作为开门段，剩余部分作为关门段；其他 legacy
   * 动画优先按“位姿变化量最大”的节点作为开门完成点（含），若无明显变化再用 “持续时间最长”的节点兜底，并反向播放作为关门段。
   */
  private static Optional<AnimationPair> buildLegacyAnimationPair(
      ConfigurationNode root, String legacyName) {
    if (root == null || legacyName == null) {
      return Optional.empty();
    }
    Optional<ConfigurationNode> animationNode = findAnimationConfig(root, legacyName);
    if (animationNode.isEmpty()) {
      return Optional.empty();
    }
    Animation base = Animation.loadFromConfig(animationNode.get());
    AnimationNode[] nodes = base.getNodeArray();
    if (nodes == null || nodes.length == 0) {
      return Optional.empty();
    }
    boolean splitByDuration = shouldSplitLegacyByDuration(legacyName);
    int openIndex = splitByDuration ? indexOfOpenPoseByDuration(nodes) : indexOfOpenPose(nodes);
    if (openIndex < 0) {
      return Optional.empty();
    }
    AnimationNode[] openNodes = cloneNodes(nodes, 0, openIndex);
    double openDuration = totalDuration(openNodes);
    Animation open = new Animation(legacyName + "_open", openNodes);
    Animation close;
    double closeDuration;
    if (splitByDuration && openIndex < nodes.length - 1) {
      AnimationNode[] closeNodes = cloneNodes(nodes, openIndex, nodes.length - 1);
      close = new Animation(legacyName + "_close", closeNodes);
      closeDuration = totalDuration(closeNodes);
    } else {
      close = new Animation(legacyName + "_close", reverseNodes(openNodes));
      closeDuration = totalDuration(openNodes);
    }
    return Optional.of(new AnimationPair(open, close, openDuration, closeDuration));
  }

  private static DoorAction buildLegacyAction(
      MinecartGroup group, String legacyName, List<Attachment> targets) {
    if (legacyName == null || targets == null) {
      return DoorAction.none();
    }
    Optional<AnimationPair> modelFallback = buildLegacyModelFallback(group, legacyName);
    List<LegacyTarget> pairs = new ArrayList<>();
    int totalTargets = targets.size();
    int splitOk = 0;
    int splitFail = 0;
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      Optional<AnimationPair> pair = buildLegacyAnimationPair(target.getConfig(), legacyName);
      if (pair.isPresent()) {
        pairs.add(new LegacyTarget(target, pair.get()));
        splitOk++;
        continue;
      }
      if (modelFallback.isPresent()) {
        pairs.add(new LegacyTarget(target, modelFallback.get()));
        splitFail++;
      }
    }
    boolean fallbackAllowed = pairs.isEmpty();
    return DoorAction.legacy(legacyName, pairs, totalTargets, splitOk, splitFail, fallbackAllowed);
  }

  private static Optional<AnimationPair> buildLegacyModelFallback(
      MinecartGroup group, String legacyName) {
    if (group == null || legacyName == null) {
      return Optional.empty();
    }
    MinecartMember<?> member = group.head();
    if (member == null || member.getProperties() == null) {
      return Optional.empty();
    }
    com.bergerkiller.bukkit.tc.attachments.config.AttachmentModel model =
        member.getProperties().getModel();
    if (model == null) {
      return Optional.empty();
    }
    return buildLegacyAnimationPair(model.getConfig(), legacyName);
  }

  private static boolean shouldSplitLegacyByDuration(String legacyName) {
    return legacyName.toLowerCase(Locale.ROOT).endsWith("10");
  }

  /**
   * legacy doorL10/doorR10：按持续时间切出前 5 秒作为开门段。
   *
   * <p>若动画总时长不足 10 秒，则按一半切分，确保还有关门段。
   */
  private static int indexOfOpenPoseByDuration(AnimationNode[] nodes) {
    if (nodes == null || nodes.length == 0) {
      return -1;
    }
    double total = totalDuration(nodes);
    if (!Double.isFinite(total) || total <= 0.0) {
      return indexOfOpenPose(nodes);
    }
    double target = total >= LEGACY_SPLIT_SECONDS * 2.0 ? LEGACY_SPLIT_SECONDS : total / 2.0;
    return indexAtDuration(nodes, target);
  }

  private static double totalDuration(AnimationNode[] nodes) {
    double total = 0.0;
    for (AnimationNode node : nodes) {
      if (node == null) {
        continue;
      }
      total += Math.max(0.0, node.getDuration());
    }
    return total;
  }

  private static int indexAtDuration(AnimationNode[] nodes, double targetSeconds) {
    if (nodes == null || nodes.length == 0) {
      return -1;
    }
    if (!Double.isFinite(targetSeconds) || targetSeconds <= 0.0) {
      return 0;
    }
    double elapsed = 0.0;
    for (int i = 0; i < nodes.length; i++) {
      AnimationNode node = nodes[i];
      if (node == null) {
        continue;
      }
      elapsed += Math.max(0.0, node.getDuration());
      if (elapsed >= targetSeconds) {
        return i;
      }
    }
    return nodes.length - 1;
  }

  private static AnimationNode[] cloneNodes(AnimationNode[] nodes, int start, int end) {
    if (nodes == null || nodes.length == 0) {
      return new AnimationNode[0];
    }
    int safeStart = Math.max(0, start);
    int safeEnd = Math.min(end, nodes.length - 1);
    int count = Math.max(0, safeEnd - safeStart + 1);
    AnimationNode[] result = new AnimationNode[count];
    for (int i = 0; i < count; i++) {
      result[i] = nodes[safeStart + i].clone();
    }
    return result;
  }

  /**
   * 寻找开门完成的节点索引。
   *
   * <p>优先使用场景标记（包含 open 的 marker），否则以位姿变化量最大为准；若没有变化，则回退到最长时长节点。
   */
  private static int indexOfOpenPose(AnimationNode[] nodes) {
    int markerIndex = indexOfOpenSceneMarker(nodes);
    if (markerIndex >= 0) {
      return markerIndex;
    }
    if (nodes == null || nodes.length == 0) {
      return 0;
    }
    AnimationNode base = nodes[0];
    Vector basePos = base.getPosition();
    Vector baseRot = base.getRotationVector();
    double maxDelta = -1.0;
    int maxIndex = 0;
    for (int i = 0; i < nodes.length; i++) {
      AnimationNode node = nodes[i];
      double delta = 0.0;
      if (basePos != null && node.getPosition() != null) {
        delta += basePos.distanceSquared(node.getPosition());
      }
      if (baseRot != null && node.getRotationVector() != null) {
        delta += baseRot.distanceSquared(node.getRotationVector());
      }
      if (delta > maxDelta) {
        maxDelta = delta;
        maxIndex = i;
      }
    }
    if (maxDelta <= 0.0) {
      return indexOfMaxDuration(nodes);
    }
    return maxIndex;
  }

  /** 在节点列表中寻找包含 “open” 的场景标记，返回最后一次出现的位置。 */
  private static int indexOfOpenSceneMarker(AnimationNode[] nodes) {
    if (nodes == null) {
      return -1;
    }
    int lastOpenIndex = -1;
    for (int i = 0; i < nodes.length; i++) {
      AnimationNode node = nodes[i];
      if (node == null) {
        continue;
      }
      String marker = node.getSceneMarker();
      if (marker == null || marker.isEmpty()) {
        continue;
      }
      String normalized = marker.toLowerCase(Locale.ROOT);
      if (normalized.contains("open")) {
        lastOpenIndex = i;
      }
    }
    return lastOpenIndex;
  }

  /** 找到持续时间最长的节点索引，用作兜底。 */
  private static int indexOfMaxDuration(AnimationNode[] nodes) {
    int maxIndex = 0;
    double maxDuration = nodes[0].getDuration();
    for (int i = 1; i < nodes.length; i++) {
      double duration = nodes[i].getDuration();
      if (duration > maxDuration) {
        maxDuration = duration;
        maxIndex = i;
      }
    }
    return maxIndex;
  }

  /** 反向复制节点数组，用于关门动画。 */
  private static AnimationNode[] reverseNodes(AnimationNode[] nodes) {
    AnimationNode[] reversed = new AnimationNode[nodes.length];
    for (int i = 0; i < nodes.length; i++) {
      reversed[i] = nodes[nodes.length - 1 - i].clone();
    }
    return reversed;
  }

  /**
   * 在模型配置中寻找指定动画节点（兼容 legacy 配置结构）。
   *
   * <p>优先从 animations 下检索，找不到则遍历子节点并匹配名字。
   */
  private static Optional<ConfigurationNode> findAnimationConfig(
      ConfigurationNode root, String name) {
    if (root == null || name == null) {
      return Optional.empty();
    }
    ConfigurationNode animations = root.getNodeIfExists("animations");
    if (animations != null && animations.contains(name)) {
      return Optional.of(animations.getNode(name));
    }
    Deque<ConfigurationNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      ConfigurationNode node = stack.pop();
      String nodeName = node.getName();
      if (nodeName != null && nodeName.equalsIgnoreCase(name) && node.contains("nodes")) {
        return Optional.of(node);
      }
      for (ConfigurationNode child : node.getNodes()) {
        stack.push(child);
      }
    }
    return Optional.empty();
  }

  /** 门动作抽象：可能是标准动画、截取的 legacy 动画或空动作。 */
  private sealed interface DoorAction
      permits DoorAction.Named, DoorAction.Custom, DoorAction.Legacy, DoorAction.None {
    boolean open(MinecartGroup group);

    boolean close(MinecartGroup group);

    long closeDurationTicks();

    static DoorAction named(String name, List<Attachment> targets) {
      return new DoorAction.Named(name, targets);
    }

    static DoorAction custom(AnimationPair pair, List<Attachment> targets) {
      return new DoorAction.Custom(pair, targets);
    }

    static DoorAction legacy(
        String name,
        List<LegacyTarget> targets,
        int totalTargets,
        int splitOk,
        int splitFail,
        boolean fallbackAllowed) {
      return new DoorAction.Legacy(
          name, targets, totalTargets, splitOk, splitFail, fallbackAllowed);
    }

    static DoorAction none() {
      return DoorAction.None.INSTANCE;
    }

    final class Named implements DoorAction {
      private final String name;
      private final List<Attachment> targets;

      private Named(String name, List<Attachment> targets) {
        this.name = name;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
      }

      @Override
      public boolean open(MinecartGroup group) {
        if (group == null || name == null || !hasAttachedTargets(targets)) {
          return false;
        }
        AnimationOptions options = new AnimationOptions(name);
        options.setReset(true);
        options.setSpeed(1.0);
        return group.playNamedAnimation(options);
      }

      @Override
      public boolean close(MinecartGroup group) {
        if (group == null || name == null || !hasAttachedTargets(targets)) {
          return false;
        }
        AnimationOptions options = new AnimationOptions(name);
        options.setReset(true);
        options.setSpeed(-1.0);
        return group.playNamedAnimation(options);
      }

      @Override
      public long closeDurationTicks() {
        return -1L;
      }
    }

    final class Custom implements DoorAction {
      private final AnimationPair pair;
      private final List<Attachment> targets;

      private Custom(AnimationPair pair, List<Attachment> targets) {
        this.pair = pair;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
      }

      @Override
      public boolean open(MinecartGroup group) {
        if (targets.isEmpty()) {
          return startAnimationOnGroup(group, pair.open());
        }
        return startAnimation(targets, pair.open());
      }

      @Override
      public boolean close(MinecartGroup group) {
        if (targets.isEmpty()) {
          return startAnimationOnGroup(group, pair.close());
        }
        return startAnimation(targets, pair.close());
      }

      @Override
      public long closeDurationTicks() {
        return -1L;
      }
    }

    final class Legacy implements DoorAction {
      private final String name;
      private final List<LegacyTarget> targets;
      private final int totalTargets;
      private final int splitOk;
      private final int splitFail;
      private final boolean fallbackAllowed;
      private boolean fallbackUsed;
      private final long closeDurationTicks;

      private Legacy(
          String name,
          List<LegacyTarget> targets,
          int totalTargets,
          int splitOk,
          int splitFail,
          boolean fallbackAllowed) {
        this.name = name;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.totalTargets = totalTargets;
        this.splitOk = splitOk;
        this.splitFail = splitFail;
        this.fallbackAllowed = fallbackAllowed;
        this.closeDurationTicks = estimateCloseDurationTicks(this.targets);
      }

      @Override
      public boolean open(MinecartGroup group) {
        boolean opened = startLegacyTargets(targets, true);
        if (opened) {
          fallbackUsed = false;
          return true;
        }
        if (!fallbackAllowed || group == null || name == null) {
          return false;
        }
        boolean played = group.playNamedAnimation(name);
        if (played) {
          fallbackUsed = true;
        }
        return played;
      }

      @Override
      public boolean close(MinecartGroup group) {
        if (fallbackUsed) {
          return false;
        }
        boolean closed = startLegacyTargets(targets, false);
        if (closed) {
          return true;
        }
        if (!fallbackAllowed || group == null || name == null) {
          return false;
        }
        AnimationOptions options = new AnimationOptions(name);
        options.setReset(true);
        options.setSpeed(-1.0);
        return group.playNamedAnimation(options);
      }

      @Override
      public long closeDurationTicks() {
        return closeDurationTicks;
      }
    }

    enum None implements DoorAction {
      INSTANCE;

      @Override
      public boolean open(MinecartGroup group) {
        return false;
      }

      @Override
      public boolean close(MinecartGroup group) {
        return false;
      }

      @Override
      public long closeDurationTicks() {
        return -1L;
      }
    }
  }

  private record AnimationPair(
      Animation open, Animation close, double openDurationSeconds, double closeDurationSeconds) {}

  private record LegacyTarget(Attachment target, AnimationPair pair) {}

  /**
   * 在所有成员的附件根节点上启动动画。
   *
   * <p>仅对已绑定附件的成员生效，避免空指针和未加载模型时误触发。
   */
  private static boolean startAnimation(List<Attachment> targets, Animation animation) {
    if (targets == null || targets.isEmpty() || animation == null) {
      return false;
    }
    boolean started = false;
    for (Attachment target : targets) {
      if (target == null || !target.isAttached()) {
        continue;
      }
      target.startAnimation(animation.clone());
      started = true;
    }
    return started;
  }

  private static boolean startLegacyTargets(List<LegacyTarget> targets, boolean open) {
    if (targets == null || targets.isEmpty()) {
      return false;
    }
    boolean started = false;
    for (LegacyTarget entry : targets) {
      if (entry == null || entry.target() == null) {
        continue;
      }
      if (!entry.target().isAttached()) {
        continue;
      }
      Animation animation = open ? entry.pair().open() : entry.pair().close();
      if (animation == null) {
        continue;
      }
      entry.target().startAnimation(animation.clone());
      started = true;
    }
    return started;
  }

  /** 在每节车厢的根附件上播放动画（缺少门附件时的兜底）。 */
  private static boolean startAnimationOnGroup(MinecartGroup group, Animation animation) {
    if (group == null || animation == null) {
      return false;
    }
    boolean started = false;
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      if (member.getAttachments() == null || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root == null) {
        continue;
      }
      root.startAnimation(animation.clone());
      started = true;
    }
    return started;
  }

  private static boolean hasAttachedTargets(List<Attachment> targets) {
    if (targets == null || targets.isEmpty()) {
      return false;
    }
    for (Attachment target : targets) {
      if (target != null && target.isAttached()) {
        return true;
      }
    }
    return false;
  }

  private static int countAttachedTargets(List<Attachment> targets) {
    if (targets == null || targets.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (Attachment target : targets) {
      if (target != null && target.isAttached()) {
        count++;
      }
    }
    return count;
  }

  private static int countAttachedLegacyTargets(List<LegacyTarget> targets) {
    if (targets == null || targets.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (LegacyTarget target : targets) {
      if (target == null || target.target() == null) {
        continue;
      }
      if (target.target().isAttached()) {
        count++;
      }
    }
    return count;
  }

  private static long estimateCloseDurationTicks(List<LegacyTarget> targets) {
    if (targets == null || targets.isEmpty()) {
      return -1L;
    }
    double maxSeconds = -1.0;
    for (LegacyTarget target : targets) {
      if (target == null || target.pair() == null) {
        continue;
      }
      double seconds = target.pair().closeDurationSeconds();
      if (Double.isFinite(seconds) && seconds > maxSeconds) {
        maxSeconds = seconds;
      }
    }
    if (!Double.isFinite(maxSeconds) || maxSeconds <= 0.0) {
      return -1L;
    }
    return Math.max(1L, Math.round(maxSeconds * 20.0));
  }

  private static String describeAction(DoorAction action, boolean enabled) {
    if (!enabled) {
      return "skip";
    }
    if (action instanceof DoorAction.Named named) {
      int attached = countAttachedTargets(named.targets);
      String sample = sampleTargets(named.targets, 2);
      return "named("
          + named.name
          + ",targets="
          + named.targets.size()
          + ",attached="
          + attached
          + (sample.isEmpty() ? "" : ",sample=" + sample)
          + ")";
    }
    if (action instanceof DoorAction.Legacy legacy) {
      int attached = countAttachedLegacyTargets(legacy.targets);
      String sample = sampleLegacyTargets(legacy.targets, 2);
      return "legacy("
          + legacy.name
          + ",targets="
          + legacy.totalTargets
          + ",attached="
          + attached
          + ",split="
          + legacy.splitOk
          + "/"
          + legacy.splitFail
          + ",fallback="
          + legacy.fallbackAllowed
          + ",fallbackUsed="
          + legacy.fallbackUsed
          + (sample.isEmpty() ? "" : ",sample=" + sample)
          + ")";
    }
    if (action instanceof DoorAction.Custom custom) {
      int attached = countAttachedTargets(custom.targets);
      String sample = sampleTargets(custom.targets, 2);
      return "custom(targets="
          + custom.targets.size()
          + ",attached="
          + attached
          + (sample.isEmpty() ? "" : ",sample=" + sample)
          + ")";
    }
    return "none";
  }

  private static String sampleLegacyTargets(List<LegacyTarget> targets, int limit) {
    if (targets == null || targets.isEmpty() || limit <= 0) {
      return "";
    }
    List<Attachment> attachments = new ArrayList<>();
    for (LegacyTarget target : targets) {
      if (target == null || target.target() == null) {
        continue;
      }
      attachments.add(target.target());
      if (attachments.size() >= limit) {
        break;
      }
    }
    String sample = sampleTargets(attachments, limit);
    if (sample.isEmpty()) {
      return "";
    }
    if (targets.size() > attachments.size()) {
      return sample + "|...";
    }
    return sample;
  }

  private static String sampleTargets(List<Attachment> targets, int limit) {
    if (targets == null || targets.isEmpty() || limit <= 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    int added = 0;
    for (Attachment target : targets) {
      if (target == null) {
        continue;
      }
      String name = safeAttachmentName(target);
      if (name.isBlank()) {
        continue;
      }
      if (added > 0) {
        sb.append("|");
      }
      sb.append(name);
      added++;
      if (added >= limit) {
        break;
      }
    }
    return sb.toString();
  }

  private static String safeAttachmentName(Attachment attachment) {
    if (attachment == null) {
      return "";
    }
    try {
      ConfigurationNode config = attachment.getConfig();
      if (config != null) {
        String name = config.getName();
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
      return attachment.getClass().getSimpleName();
    } catch (Throwable ignored) {
      return "";
    }
  }

  /**
   * 在关门时播放提示音。
   *
   * <p>只有附近存在玩家时才播放，避免无观众时的额外计算。
   */
  private static void playCloseSound(
      MinecartGroup group, boolean openLeft, boolean openRight, DoorChimeSettings settings) {
    if (group == null || (!openLeft && !openRight)) {
      return;
    }
    DoorChimeSettings resolved = settings == null ? DoorChimeSettings.none() : settings;
    boolean playedCustom =
        playChimeFromAttachments(group, ChimeType.CLOSE, resolved, true /* allowDefault */);
    if (playedCustom) {
      return;
    }
    if (!resolved.defaultCloseSound().isEnabled()) {
      return;
    }
    playDefaultSoundAtDoors(group, openLeft, openRight, resolved);
  }

  /**
   * 播放开门提示音。
   *
   * <p>开门仅使用附件上的自定义提示音，不提供默认音。
   */
  private static void playOpenChime(
      MinecartGroup group, boolean openLeft, boolean openRight, DoorChimeSettings settings) {
    if (group == null || (!openLeft && !openRight)) {
      return;
    }
    DoorChimeSettings resolved = settings == null ? DoorChimeSettings.none() : settings;
    playChimeFromAttachments(group, ChimeType.OPEN, resolved, false /* allowDefault */);
  }

  /**
   * 在门附件位置播放默认关门提示音。
   *
   * <p>若找不到门附件，则回退到车体位置播放。
   */
  private static void playDefaultSoundAtDoors(
      MinecartGroup group, boolean openLeft, boolean openRight, DoorChimeSettings settings) {
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      if (member.getAttachments() == null || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root == null) {
        continue;
      }
      Collection<Player> viewers = root.getViewers();
      if (viewers == null || viewers.isEmpty()) {
        continue;
      }
      World world = member.getWorld();
      if (world == null) {
        continue;
      }
      List<Location> locations = new ArrayList<>();
      if (openLeft) {
        collectDoorLocations(root, world, LEFT_ANIMATIONS, locations);
      }
      if (openRight) {
        collectDoorLocations(root, world, RIGHT_ANIMATIONS, locations);
      }
      if (locations.isEmpty()) {
        Location fallback = member.getBlock(0, 0, 0).getLocation();
        locations.add(fallback);
      }
      for (Location location : locations) {
        if (location == null) {
          continue;
        }
        if (!hasNearbyViewer(viewers, location)) {
          continue;
        }
        playChimeSequence(member, location, settings.defaultCloseSound());
      }
    }
  }

  /**
   * 从附件配置中读取 sequencer 标记并播放提示音。
   *
   * <p>可选回退到默认关门声音（仅关门时启用）。
   */
  private static boolean playChimeFromAttachments(
      MinecartGroup group, ChimeType type, DoorChimeSettings settings, boolean allowDefault) {
    if (group == null) {
      return false;
    }
    boolean played = false;
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      if (member.getAttachments() == null || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root == null) {
        continue;
      }
      Collection<Player> viewers = root.getViewers();
      if (viewers == null || viewers.isEmpty()) {
        continue;
      }
      World world = member.getWorld();
      if (world == null) {
        continue;
      }
      List<ChimeLocation> locations = new ArrayList<>();
      collectChimeLocations(root, type, locations);
      if (locations.isEmpty()) {
        continue;
      }
      for (ChimeLocation location : locations) {
        if (location == null || location.location() == null) {
          continue;
        }
        if (!hasNearbyViewer(viewers, location.location())) {
          continue;
        }
        ChimeSound sound =
            location.override() != null
                ? location.override()
                : allowDefault ? settings.defaultCloseSound() : null;
        if (sound == null || !sound.isEnabled()) {
          continue;
        }
        playChimeSequence(member, location.location(), sound);
        played = true;
      }
    }
    return played;
  }

  /** 以固定间隔重复播放提示音。 */
  private static void playChimeSequence(
      MinecartMember<?> member, Location location, ChimeSound sound) {
    if (member == null || location == null || sound == null || !sound.isEnabled()) {
      return;
    }
    World world = location.getWorld();
    if (world == null) {
      return;
    }
    for (int i = 0; i < CHIME_REPEAT_COUNT; i++) {
      long delay = i * CHIME_REPEAT_INTERVAL_TICKS;
      if (delay <= 0) {
        sound.play(world, location);
        continue;
      }
      Bukkit.getScheduler()
          .runTaskLater(member.getTrainCarts(), () -> sound.play(world, location), delay);
    }
  }

  /**
   * 扫描附件配置中的 chime 标记并记录播放位置。
   *
   * <p>支持在附件上配置音效覆盖参数。
   */
  private static void collectChimeLocations(
      Attachment attachment, ChimeType type, List<ChimeLocation> locations) {
    if (attachment == null || type == null || locations == null) {
      return;
    }
    ChimeSound override = readChimeSound(attachment.getConfig(), type);
    if (override != null) {
      World world = attachment.getManager() == null ? null : attachment.getManager().getWorld();
      Location location = null;
      if (world != null) {
        location = attachment.getTransform().toLocation(world);
      }
      if (location != null) {
        locations.add(new ChimeLocation(location, override));
      }
    } else if (hasChimeMarker(attachment.getConfig(), type)) {
      World world = attachment.getManager() == null ? null : attachment.getManager().getWorld();
      Location location = null;
      if (world != null) {
        location = attachment.getTransform().toLocation(world);
      }
      if (location != null) {
        locations.add(new ChimeLocation(location, null));
      }
    }
    for (Attachment child : attachment.getChildren()) {
      collectChimeLocations(child, type, locations);
    }
  }

  /**
   * 解析附件配置中的提示音参数。
   *
   * <p>键名支持 sound / sound_key / sound-key。
   */
  private static ChimeSound readChimeSound(ConfigurationNode config, ChimeType type) {
    if (config == null || type == null) {
      return null;
    }
    if (!hasChimeMarker(config, type)) {
      return null;
    }
    String raw =
        firstNonEmpty(
            config.get("sound", String.class),
            config.get("sound_key", String.class),
            config.get("sound-key", String.class));
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String soundKey = raw.trim();
    if ("none".equalsIgnoreCase(soundKey)) {
      return null;
    }
    double volume = config.get("volume", double.class, 1.0);
    double pitch = config.get("pitch", double.class, 1.0);
    return ChimeSound.from(soundKey, (float) volume, (float) pitch);
  }

  /** 判断附件是否包含匹配的 sequencer 标记。 */
  private static boolean hasChimeMarker(ConfigurationNode config, ChimeType type) {
    if (config == null || type == null) {
      return false;
    }
    List<String> names = new ArrayList<>();
    String single = config.get("sequencer", String.class);
    if (single != null && !single.isBlank()) {
      names.add(single);
    }
    names.addAll(config.getList("sequencer", String.class, List.of()));
    names.addAll(config.getList("sequencers", String.class, List.of()));
    if (names.isEmpty()) {
      return false;
    }
    for (String name : names) {
      if (name == null) {
        continue;
      }
      String normalized = name.trim();
      if (normalized.isEmpty()) {
        continue;
      }
      if (type.matches(normalized)) {
        return true;
      }
    }
    return false;
  }

  private static String firstNonEmpty(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      String trimmed = candidate.trim();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return null;
  }

  /** 收集带门动画的附件位置，用于默认提示音定位。 */
  private static void collectDoorLocations(
      Attachment attachment, World world, Set<String> animations, List<Location> locations) {
    if (attachment == null || world == null || animations == null || locations == null) {
      return;
    }
    if (containsAnyAnimation(attachment.getAnimationNames(), animations)) {
      Location location = attachment.getTransform().toLocation(world);
      if (location != null) {
        locations.add(location);
      }
    }
    for (Attachment child : attachment.getChildren()) {
      collectDoorLocations(child, world, animations, locations);
    }
  }

  private static boolean containsAnyAnimation(Collection<String> names, Set<String> targets) {
    if (names == null || names.isEmpty() || targets == null || targets.isEmpty()) {
      return false;
    }
    for (String name : names) {
      if (name == null) {
        continue;
      }
      String normalized = name.trim().toLowerCase(Locale.ROOT);
      if (targets.contains(normalized)) {
        return true;
      }
    }
    return false;
  }

  private static DoorSideSelection axisSideSelection(
      World world,
      BlockFace desired,
      List<Attachment> leftTargets,
      List<Attachment> rightTargets,
      double eps) {
    if (world == null || desired == null) {
      return null;
    }
    List<Double> leftScores = collectAxisScores(world, desired, leftTargets);
    List<Double> rightScores = collectAxisScores(world, desired, rightTargets);
    if (leftScores.isEmpty() && rightScores.isEmpty()) {
      return null;
    }
    Double leftMedian = medianScore(leftScores);
    Double rightMedian = medianScore(rightScores);
    boolean hasLeft = leftMedian != null && Double.isFinite(leftMedian);
    boolean hasRight = rightMedian != null && Double.isFinite(rightMedian);
    if (hasLeft && hasRight) {
      double delta = leftMedian - rightMedian;
      if (Math.abs(delta) > eps) {
        return delta > 0.0
            ? new DoorSideSelection(true, false)
            : new DoorSideSelection(false, true);
      }
      return null;
    }
    if (hasLeft) {
      return Math.abs(leftMedian) > eps ? new DoorSideSelection(true, false) : null;
    }
    if (hasRight) {
      return Math.abs(rightMedian) > eps ? new DoorSideSelection(false, true) : null;
    }
    return null;
  }

  private static List<Double> collectAxisScores(
      World world, BlockFace desired, List<Attachment> targets) {
    if (world == null || desired == null || targets == null || targets.isEmpty()) {
      return List.of();
    }
    List<Double> scores = new ArrayList<>();
    for (Attachment target : targets) {
      if (target == null || !target.isAttached()) {
        continue;
      }
      Attachment anchor = resolveDoorAnchor(target);
      if (anchor == null || !anchor.isAttached()) {
        continue;
      }
      Vector direction = resolveWorldDirection(world, anchor, new Vector(1.0, 0.0, 0.0));
      if (direction == null) {
        continue;
      }
      double score = desiredAxisScore(desired, direction);
      if (Double.isFinite(score)) {
        scores.add(score);
      }
    }
    return scores;
  }

  private static Attachment resolveDoorAnchor(Attachment attachment) {
    if (attachment == null) {
      return null;
    }
    Attachment parent = attachment.getParent();
    return parent != null ? parent : attachment;
  }

  private static Vector resolveWorldDirection(World world, Attachment anchor, Vector localAxis) {
    if (world == null || anchor == null || localAxis == null) {
      return null;
    }
    com.bergerkiller.bukkit.common.math.Matrix4x4 transform = anchor.getTransform();
    if (transform == null) {
      return null;
    }
    Location origin = transform.toLocation(world);
    if (origin == null) {
      return null;
    }
    Vector point = localAxis.clone();
    transform.transformPoint(point);
    Vector direction = point.subtract(origin.toVector());
    double length = direction.length();
    if (!Double.isFinite(length) || length <= 1.0e-6) {
      return null;
    }
    return direction.multiply(1.0 / length);
  }

  private static double desiredAxisScore(BlockFace desired, Vector direction) {
    if (direction == null || desired == null) {
      return Double.NaN;
    }
    double sign = desiredSign(desired);
    if (desired == BlockFace.NORTH || desired == BlockFace.SOUTH) {
      return sign * direction.getZ();
    }
    if (desired == BlockFace.EAST || desired == BlockFace.WEST) {
      return sign * direction.getX();
    }
    return Double.NaN;
  }

  private static Double medianScore(List<Double> scores) {
    if (scores == null || scores.isEmpty()) {
      return null;
    }
    List<Double> sorted = new ArrayList<>(scores);
    sorted.sort(Double::compare);
    int size = sorted.size();
    int midIndex = size / 2;
    if (size % 2 == 1) {
      return sorted.get(midIndex);
    }
    return (sorted.get(midIndex - 1) + sorted.get(midIndex)) / 2.0;
  }

  private static DoorSideSelection maxSeparationSelection(
      World world,
      BlockFace desired,
      List<Attachment> leftTargets,
      List<Attachment> rightTargets,
      int anchorDepth,
      WorldSelectProbe probe,
      double eps) {
    if (world == null || desired == null) {
      return null;
    }
    List<Location> leftLocations = collectAttachedLocations(world, leftTargets, anchorDepth, probe);
    List<Location> rightLocations =
        collectAttachedLocations(world, rightTargets, anchorDepth, probe);
    if (leftLocations.isEmpty() || rightLocations.isEmpty()) {
      return null;
    }
    double bestAbs = -1.0;
    DoorSideSelection best = null;
    for (Location left : leftLocations) {
      if (left == null) {
        continue;
      }
      double scoreL = desiredScore(desired, left);
      if (!Double.isFinite(scoreL)) {
        continue;
      }
      for (Location right : rightLocations) {
        if (right == null) {
          continue;
        }
        double scoreR = desiredScore(desired, right);
        if (!Double.isFinite(scoreR)) {
          continue;
        }
        double delta = scoreL - scoreR;
        double abs = Math.abs(delta);
        if (abs > bestAbs + 1.0e-9) {
          bestAbs = abs;
          best =
              delta > 0.0 ? new DoorSideSelection(true, false) : new DoorSideSelection(false, true);
        }
      }
    }
    if (bestAbs <= eps) {
      return null;
    }
    return best;
  }

  /** 判断门附近是否存在观察者，避免无人时播放提示音。 */
  private static boolean hasNearbyViewer(Collection<Player> viewers, Location location) {
    if (viewers == null || location == null) {
      return false;
    }
    double radiusSquared = DOOR_SOUND_RADIUS_BLOCKS * DOOR_SOUND_RADIUS_BLOCKS;
    for (Player player : viewers) {
      if (player == null || player.getWorld() != location.getWorld()) {
        continue;
      }
      if (player.getLocation().distanceSquared(location) <= radiusSquared) {
        return true;
      }
    }
    return false;
  }

  static String sampleDoorTransformSummary(MinecartGroup group) {
    if (group == null) {
      return "none";
    }
    Collection<String> animationNames = group.getAnimationNames();
    if (animationNames == null || animationNames.isEmpty()) {
      return "none";
    }
    String leftName = findAnimationName(animationNames, DOOR_LEFT);
    leftName = leftName != null ? leftName : findAnimationName(animationNames, DOOR_LEFT_LEGACY);
    String rightName = findAnimationName(animationNames, DOOR_RIGHT);
    rightName =
        rightName != null ? rightName : findAnimationName(animationNames, DOOR_RIGHT_LEGACY);
    String leftSummary = sampleTransform(group, leftName);
    String rightSummary = sampleTransform(group, rightName);
    if ("none".equals(leftSummary) && "none".equals(rightSummary)) {
      return "none";
    }
    return "L=" + leftSummary + ",R=" + rightSummary;
  }

  private static String sampleTransform(MinecartGroup group, String animationName) {
    if (group == null || animationName == null) {
      return "none";
    }
    List<Attachment> targets = findAnimationTargets(group, animationName);
    for (Attachment target : targets) {
      if (target == null || !target.isAttached()) {
        continue;
      }
      Attachment parent = target.getParent();
      String child = formatTransform(target);
      String parentSummary = parent != null ? formatTransform(parent) : "none";
      return "child=" + child + ",parent=" + parentSummary;
    }
    return "none";
  }

  private static String formatTransform(Attachment attachment) {
    if (attachment == null || attachment.getManager() == null) {
      return "none";
    }
    World world = attachment.getManager().getWorld();
    if (world == null) {
      return "none";
    }
    com.bergerkiller.bukkit.common.math.Matrix4x4 transform = attachment.getTransform();
    if (transform == null) {
      return "none";
    }
    Location location = transform.toLocation(world);
    if (location == null) {
      return "none";
    }
    return String.format(Locale.ROOT, "(%.2f,%.2f)", location.getX(), location.getZ());
  }

  /** 提示音触发类型：开门/关门。 */
  enum ChimeType {
    OPEN,
    CLOSE;

    boolean matches(String name) {
      if (name == null) {
        return false;
      }
      String normalized = name.trim();
      if (normalized.isEmpty()) {
        return false;
      }
      if (CHIME_GENERIC.equalsIgnoreCase(normalized)) {
        return true;
      }
      if (this == OPEN) {
        return CHIME_OPEN.equalsIgnoreCase(normalized);
      }
      return CHIME_CLOSE.equalsIgnoreCase(normalized);
    }
  }

  /** 提示音配置：目前仅包含默认关门提示音。 */
  static final class DoorChimeSettings {
    private final ChimeSound defaultCloseSound;

    private DoorChimeSettings(ChimeSound defaultCloseSound) {
      this.defaultCloseSound =
          defaultCloseSound == null ? ChimeSound.disabled() : defaultCloseSound;
    }

    static DoorChimeSettings none() {
      return new DoorChimeSettings(ChimeSound.disabled());
    }

    static DoorChimeSettings fromConfig(String soundKey, float volume, float pitch) {
      if (soundKey == null || soundKey.isBlank() || "none".equalsIgnoreCase(soundKey)) {
        return none();
      }
      return new DoorChimeSettings(ChimeSound.from(soundKey, volume, pitch));
    }

    ChimeSound defaultCloseSound() {
      return defaultCloseSound;
    }
  }

  private record ChimeLocation(Location location, ChimeSound override) {}

  /** 支持 Bukkit Sound 或自定义 sound key 的提示音封装。 */
  private record ChimeSound(Sound enumSound, String soundKey, float volume, float pitch) {
    static ChimeSound from(String raw, float volume, float pitch) {
      if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw)) {
        return disabled();
      }
      Sound enumSound = parseEnumSound(raw);
      String key = enumSound == null ? raw : null;
      return new ChimeSound(enumSound, key, sanitizeVolume(volume), sanitizePitch(pitch));
    }

    static ChimeSound disabled() {
      return new ChimeSound(null, null, 1.0f, 1.0f);
    }

    boolean isEnabled() {
      return enumSound != null || (soundKey != null && !soundKey.isBlank());
    }

    void play(World world, Location location) {
      if (world == null || location == null || !isEnabled()) {
        return;
      }
      if (enumSound != null) {
        world.playSound(location, enumSound, volume, pitch);
        return;
      }
      world.playSound(location, soundKey, volume, pitch);
    }

    private static float sanitizeVolume(float value) {
      if (Float.isFinite(value) && value > 0.0f) {
        return value;
      }
      return 1.0f;
    }

    private static float sanitizePitch(float value) {
      if (Float.isFinite(value) && value > 0.0f) {
        return value;
      }
      return 1.0f;
    }

    private static Sound parseEnumSound(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }
}
