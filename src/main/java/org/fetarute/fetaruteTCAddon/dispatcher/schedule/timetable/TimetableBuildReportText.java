package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构建报告里的文案：冲突摘要与明细、取消班次的归组说明。
 *
 * <p>从 {@link TimetableBuilder} 抽出来只有一个理由：builder 要回答"表是什么"，不该同时负责"怎么把它说给人听"。 时刻一律走 {@link
 * TimetableCsvExporter#clock}，跨零点带 +1、早于零点带 -1。
 */
public final class TimetableBuildReportText {

  /** 取消班次的警告里最多列出多少个时刻。 */
  static final int DROPPED_TIMES_IN_WARNING = 6;

  private TimetableBuildReportText() {}

  /** "目标间隔 300s 有 N 处冲突（区间 x、站台 y）"。 */
  public static String summarizeConflicts(
      TimetableConflictChecker.Report report, int targetHeadway) {
    StringBuilder kinds = new StringBuilder();
    report
        .countByKind()
        .forEach(
            (kind, count) -> {
              if (kinds.length() > 0) {
                kinds.append("、");
              }
              kinds.append(describe(kind)).append(' ').append(count);
            });
    return "目标间隔 " + targetHeadway + "s 有 " + report.conflicts().size() + " 处冲突（" + kinds + "）";
  }

  /** 目标间隔下"运行时也让不掉"的那些：这才是判定不可行的量。 */
  public static String summarizeUnabsorbable(
      List<ConflictAbsorption.Residual> unabsorbable, int targetHeadway) {
    StringBuilder reasons = new StringBuilder();
    ConflictAbsorption.countByVerdict(unabsorbable)
        .forEach(
            (verdict, count) -> {
              if (reasons.length() > 0) {
                reasons.append("、");
              }
              reasons.append(describe(verdict)).append(' ').append(count);
            });
    return "目标间隔 " + targetHeadway + "s 有 " + unabsorbable.size() + " 处运行时让不掉的冲突（" + reasons + "）";
  }

  /**
   * 残余两行：可吸收的与不可吸收的。
   *
   * @param absorbable 可吸收残余
   * @param unabsorbable 不可吸收残余
   * @param holdMaxSeconds 站台扣留上限：用来区分让车发生在站台还是资源前
   * @param serviceStartSecondOfDay 零点，明细里的相对秒换算成时钟用
   * @param detailLimit 明细最多列几条
   */
  public static List<String> describeResiduals(
      List<ConflictAbsorption.Residual> absorbable,
      List<ConflictAbsorption.Residual> unabsorbable,
      int holdMaxSeconds,
      int serviceStartSecondOfDay,
      int detailLimit) {
    List<String> out = new ArrayList<>();
    if (absorbable.isEmpty() && unabsorbable.isEmpty()) {
      return out;
    }
    if (!absorbable.isEmpty()) {
      int longest =
          absorbable.stream().mapToInt(ConflictAbsorption.Residual::waitSeconds).max().orElse(0);
      long beyondHold = absorbable.stream().filter(r -> r.waitSeconds() > holdMaxSeconds).count();
      long trips = absorbable.stream().map(ConflictAbsorption.Residual::mover).distinct().count();
      out.add(
          String.format(
              Locale.ROOT,
              "  残余冲突: %d 处可吸收（最长预计等待 %ds；其中 %d 处超过 hold-max %ds，运行时在资源前等；涉及 %d 班）",
              absorbable.size(),
              longest,
              beyondHold,
              holdMaxSeconds,
              trips));
      int shown = Math.min(absorbable.size(), Math.max(0, detailLimit));
      for (int i = 0; i < shown; i++) {
        ConflictAbsorption.Residual residual = absorbable.get(i);
        out.add(
            String.format(
                Locale.ROOT,
                "    · %s 等 %ds 于 %s（%s）",
                residual.mover(),
                residual.waitSeconds(),
                waitingPointText(residual),
                residual.conflict().resource()));
      }
      if (absorbable.size() > shown) {
        out.add("    · … 另有 " + (absorbable.size() - shown) + " 处");
      }
    }
    if (!unabsorbable.isEmpty()) {
      StringBuilder reasons = new StringBuilder();
      ConflictAbsorption.countByVerdict(unabsorbable)
          .forEach(
              (verdict, count) -> {
                if (reasons.length() > 0) {
                  reasons.append(" / ");
                }
                reasons.append(describe(verdict)).append(' ').append(count);
              });
      out.add(String.format(Locale.ROOT, "  不可吸收: %d 处（%s）", unabsorbable.size(), reasons));
    }
    return out;
  }

  /** 第三层选了哪些 δ；一个都没选时说清楚"评估过但没有更好的"，而不是默不作声。 */
  public static List<String> describeResourcePhases(List<String> resourceNotes) {
    if (resourceNotes == null || resourceNotes.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    out.add("  资源相位: " + resourceNotes.get(0));
    for (int i = 1; i < resourceNotes.size(); i++) {
      out.add("    · " + resourceNotes.get(i));
    }
    return out;
  }

  /**
   * 往返对余数：<b>只报告，不参与决策</b>。
   *
   * <p>余数与冲突数没有稳定关系：余数小的间隔可能冲突很多，余数大的反而可能一处没有。 据它跳档会跳掉本来干净的间隔， 所以这一行是给人看的，不是给搜索用的。
   */
  public static List<String> describeResidues(List<PhasePlanner.Residue> residues) {
    if (residues == null || residues.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (PhasePlanner.Residue residue : residues) {
      out.add(
          String.format(
              Locale.ROOT,
              "  往返对 %s / %s：走行 %d + 折返 %d ≡ %ds（间隔 %d）",
              residue.forwardKey(),
              residue.reverseKey(),
              residue.runSeconds(),
              residue.turnaroundSeconds(),
              residue.residue(),
              residue.intervalSeconds()));
    }
    return out;
  }

  /** 判决的中文说法。 */
  /** 让车点的显示：空串是车库，缺席是判不出——两者不是一回事，报告里别混成一句。 */
  private static String waitingPointText(ConflictAbsorption.Residual residual) {
    return residual
        .waitingPoint()
        .map(group -> group.isBlank() ? "车库" : group)
        .orElse("资源前（让车点判不出）");
  }

  private static String describe(ConflictAbsorption.Verdict verdict) {
    return switch (verdict) {
      case ABSORBABLE -> "可吸收";
      case EXTERNAL -> "邻表";
      case STUB_TERMINAL -> "容量 1 端点";
      case OVER_MAX_WAIT -> "超过 max-wait";
      case NO_WAITING_CAPACITY -> "让车点无容量";
      case CHAIN_TOO_DEEP -> "顺推超限";
    };
  }

  /** 冲突明细，最多列前几条；相对秒数换算成当日时钟。 */
  public static List<String> describeConflicts(
      TimetableConflictChecker.Report report, int serviceStartSecondOfDay) {
    List<String> out = new ArrayList<>();
    int shown = Math.min(report.conflicts().size(), TimetableBuildResult.CONFLICT_DETAIL_LIMIT);
    for (int i = 0; i < shown; i++) {
      out.add(
          "  · "
              + report
                  .conflicts()
                  .get(i)
                  .describe(
                      seconds -> TimetableCsvExporter.clock(serviceStartSecondOfDay + seconds)));
    }
    if (report.conflicts().size() > shown) {
      out.add("  · … 另有 " + (report.conflicts().size() - shown) + " 处");
    }
    return out;
  }

  /** 取消的班次按 route + 原因归组，每组列出前几个时刻。 */
  public static List<String> describeDropped(List<TimetableBuildResult.DroppedTrip> dropped) {
    Map<String, List<TimetableBuildResult.DroppedTrip>> groups = new LinkedHashMap<>();
    for (TimetableBuildResult.DroppedTrip trip : dropped) {
      groups
          .computeIfAbsent(trip.routeCode() + "|" + trip.reason(), key -> new ArrayList<>())
          .add(trip);
    }
    List<String> out = new ArrayList<>(groups.size());
    for (List<TimetableBuildResult.DroppedTrip> group : groups.values()) {
      TimetableBuildResult.DroppedTrip first = group.get(0);
      StringBuilder times = new StringBuilder();
      for (int i = 0; i < Math.min(group.size(), DROPPED_TIMES_IN_WARNING); i++) {
        if (i > 0) {
          times.append(", ");
        }
        times.append(group.get(i).departureText());
      }
      if (group.size() > DROPPED_TIMES_IN_WARNING) {
        times.append(" …");
      }
      out.add(
          String.format(
              Locale.ROOT,
              "route %s 取消 %d 班（%s）: %s",
              first.routeCode(),
              group.size(),
              describe(first.reason()),
              times));
    }
    return out;
  }

  public static String describe(TimetableConflictChecker.Kind kind) {
    return switch (kind) {
      case TRACK -> "区间";
      case PLATFORM -> "站台";
      case SINGLE_LINE -> "单线对向";
      case JUNCTION -> "道岔";
    };
  }

  public static String describe(VehicleDutyPlanner.UnassignedReason reason) {
    return switch (reason) {
      case NO_CREATE_ACCESS -> "起点没有 CREATE 线路，也没有接得上的待命车";
      case NO_RETURN_ACCESS -> "终点没有 RETURN 线路，后面也接不上能回库的班次";
      case EXCEEDS_DUTY_LIMITS -> "单独一班连同出库、回库走行就超过交路时长上限";
      case STUB_SATURATED -> "端点排队或让车累计超限，等到能发车时交路已超上限或越过计划窗口";
      case CONSIST_MISMATCH -> "起点有车在等，但车型都不许跑这一班，也没有能出许可车型的出库线路";
    };
  }

  /** 让车一行："让车: N 处已写进表（涉及 M 班，最长 +Xs，--max-wait Ys；对邻表 K 处）"。 */
  public static String describeYields(List<ResourceRepair.Yield> yields, int maxWaitSeconds) {
    if (maxWaitSeconds <= 0) {
      return "让车: 关闭（--max-wait 0），冲突原样上报";
    }
    if (yields == null || yields.isEmpty()) {
      return "让车: 无（--max-wait " + maxWaitSeconds + "s）";
    }
    long external = yields.stream().filter(ResourceRepair.Yield::external).count();
    Map<TimetableConflictChecker.Kind, Integer> byKind = new LinkedHashMap<>();
    for (ResourceRepair.Yield yield : yields) {
      byKind.merge(yield.kind(), 1, Integer::sum);
    }
    StringBuilder kinds = new StringBuilder();
    byKind.forEach(
        (kind, count) -> {
          if (kinds.length() > 0) {
            kinds.append("、");
          }
          kinds.append(describe(kind)).append(' ').append(count);
        });
    return String.format(
        Locale.ROOT,
        "让车: %d 处已写进表（涉及 %d 班，最长 +%ds，--max-wait %ds）%s；%s",
        yields.size(),
        ResourceRepair.movedCount(yields),
        ResourceRepair.maxWait(yields),
        maxWaitSeconds,
        external > 0 ? "，其中对邻表让车 " + external + " 处" : "",
        kinds);
  }

  /** 让车明细，最多前几条："· 站台 platform:OP:S:A:1: RA-002 让 D001 +40s（05:05:00）"。 */
  public static List<String> describeYieldDetails(
      List<ResourceRepair.Yield> yields, int serviceStartSecondOfDay, int limit) {
    List<String> out = new ArrayList<>();
    if (yields == null || yields.isEmpty()) {
      return out;
    }
    int shown = Math.min(yields.size(), Math.max(0, limit));
    for (int i = 0; i < shown; i++) {
      ResourceRepair.Yield yield = yields.get(i);
      out.add(
          String.format(
              Locale.ROOT,
              "· %s %s: %s 让 %s +%ds（%s）",
              describe(yield.kind()),
              yield.resource(),
              yield.second(),
              yield.firstOwner().map(owner -> owner + " " + yield.first()).orElse(yield.first()),
              yield.waitSeconds(),
              TimetableCsvExporter.clock(serviceStartSecondOfDay + yield.atSeconds())));
    }
    if (yields.size() > shown) {
      out.add("· … 另有 " + (yields.size() - shown) + " 处");
    }
    return out;
  }

  /** 每个交路组一行：目标间隔（来源）→ 实际间隔。 */
  public static List<String> describeGroups(
      List<TimetableBuildResult.GroupInterval> intervals, Map<String, String> sources) {
    List<String> out = new ArrayList<>();
    for (TimetableBuildResult.GroupInterval interval : intervals) {
      String source = sources == null ? null : sources.get(interval.group());
      out.add(
          "交路组 "
              + interval.group()
              + ": 每方向 "
              + interval.targetSeconds()
              + "s"
              + (source == null ? "" : "（" + source + "）")
              + (interval.effectiveSeconds() != interval.targetSeconds()
                  ? " → 放宽到 " + interval.effectiveSeconds() + "s"
                  : ""));
    }
    return out;
  }

  /** 共用起点上的合成间隔：大小交路交错得好不好，看这一行。 */
  public static List<String> describeInterleaves(List<PhasePlanner.Interleave> interleaves) {
    List<String> out = new ArrayList<>();
    for (PhasePlanner.Interleave interleave : interleaves) {
      out.add(
          String.format(
              Locale.ROOT,
              "合成间隔 %s: %d 班，相邻 min %ds / med %ds / max %ds",
              interleave.originGroup(),
              interleave.departures(),
              interleave.minGap(),
              interleave.medianGap(),
              interleave.maxGap()));
    }
    return out;
  }

  /** 交路形状："跑 N 班的交路 M 条"，出入库班配得多不多看这一行。 */
  public static String describeDutyShapes(List<TimetableBuildResult.DutyShape> shapes) {
    if (shapes == null || shapes.isEmpty()) {
      return "交路形状: 无";
    }
    StringBuilder text = new StringBuilder("交路形状: ");
    for (int i = 0; i < shapes.size(); i++) {
      if (i > 0) {
        text.append("，");
      }
      text.append(shapes.get(i).trips())
          .append(" 班 × ")
          .append(shapes.get(i).duties())
          .append(" 条");
    }
    return text.toString();
  }

  /**
   * 「结构下界」与「端点串行」两行；没有容量 1 的端点时为空。
   *
   * <p>折返秒数<b>从该端点的占用成本里反推</b>而不是另外传一个数：打印出来的必须就是算下界时用的那个， 否则报告会和它自己解释的算式对不上。来源（终到站 dwell 还是 {@code
   * --turnaround} 覆盖）由 {@code turnarounds} 说明。
   */
  public static List<String> describeTerminals(
      List<TerminalSerializer.TerminalReport> terminals,
      List<TimetableBuildResult.TripShift> shifts,
      TurnaroundTable turnarounds) {
    List<String> out = new ArrayList<>();
    if (terminals == null || terminals.isEmpty()) {
      return out;
    }
    long delayed =
        shifts.stream().filter(s -> s.actualSecondOfDay() > s.nominalSecondOfDay()).count();
    long advanced = shifts.size() - delayed;
    int maxDelay =
        shifts.stream()
            .mapToInt(s -> s.actualSecondOfDay() - s.nominalSecondOfDay())
            .max()
            .orElse(0);
    for (TerminalSerializer.TerminalReport terminal : terminals) {
      out.add(
          String.format(
              Locale.ROOT,
              "结构下界: %s（单股道）每次折返占用 ≥ %ds（进站 %d + 折返 %d + 出站 %d + 裕量 %d，折返取 %s），按权重每 %d 班经过 %.1f 次 → 全线间隔下界 %ds",
              terminal.group(),
              terminal.visitCostSeconds(),
              terminal.approachInSeconds(),
              turnaroundOf(terminal),
              terminal.approachOutSeconds(),
              terminal.separationSeconds(),
              turnarounds.describe(),
              terminal.cycleTrips(),
              terminal.visitsPerCycle(),
              terminal.headwayFloorSeconds()));
      out.add(
          String.format(
              Locale.ROOT,
              "端点串行: %s 经过 %d 次、占用 %.0f%%%s；%d 班偏离网格（延后 %d / 提前 %d，最大 +%ds）；无处等待 %d 班；截断 %d 班",
              terminal.group(),
              terminal.visits(),
              terminal.utilization() * 100.0D,
              terminal.utilization() > 1.0D ? "（超过 100%，目标间隔本身在结构上不可能）" : "",
              shifts.size(),
              delayed,
              advanced,
              Math.max(0, maxDelay),
              terminal.nowhereToWait(),
              terminal.truncated()));
    }
    return out;
  }

  /** 「车库咽喉」一行：出库与回库共用的那段单线上各过了几次、占用多少、出库在车库里等了几班。 没有咽喉（出入段分线或没有出入库）时为空。 */
  public static List<String> describeThroats(List<TerminalSerializer.ThroatReport> throats) {
    List<String> out = new ArrayList<>();
    if (throats == null) {
      return out;
    }
    for (TerminalSerializer.ThroatReport throat : throats) {
      out.add(
          String.format(
              Locale.ROOT,
              "车库咽喉: %s（出入段共用 %d 条边）出库 %d 次、回库 %d 次、占用 %.0f%%%s；出库在库内等 %d 班（最长 +%ds）；回库撞上已排出库 %d 次",
              throat.depot(),
              throat.edges(),
              throat.outbound(),
              throat.inbound(),
              throat.utilization() * 100.0D,
              throat.utilization() > 1.0D ? "（超过 100%，目标间隔本身在结构上不可能）" : "",
              throat.waited(),
              throat.maxWaitSeconds(),
              throat.inboundOverlaps()));
    }
    return out;
  }

  /**
   * 「瓶颈」一节：最忙一小时里闭塞时间占用最高的几个资源，每次占用的组成，以及最紧的一个要省多少才轮到下一个。
   *
   * <p>占用率是按现有班次结构等比压缩的余地（见 {@link CapacityReport}）：最紧的资源占 U，所有间隔最多压到现在的 U 倍；超过 100% 是表上已有闭塞时间重叠。
   *
   * @param report 瓶颈报告
   * @param serviceStartSecondOfDay 计划窗口起点（日内秒），把相对时刻换成钟点
   * @return 报告行；没有可报的资源时为空
   */
  public static List<String> describeBottlenecks(
      CapacityReport.Report report, int serviceStartSecondOfDay) {
    List<String> out = new ArrayList<>();
    if (report == null || report.top().isEmpty()) {
      return out;
    }
    List<CapacityReport.Bottleneck> bottlenecks = report.top();
    out.add("瓶颈（最忙一小时的闭塞时间占用，各小时一样忙时取最早；按现有班次结构等比压缩，最紧的资源先占满）:");
    for (CapacityReport.Bottleneck one : bottlenecks) {
      out.add(
          String.format(
              Locale.ROOT,
              "  %s  %.0f%%（%s 起 %d 次，每次 %.0fs：%s）%s",
              resourceLabel(one),
              one.utilization() * 100.0D,
              TimetableCsvExporter.clock(serviceStartSecondOfDay + one.peakStartSeconds()),
              one.passes(),
              one.perPassSeconds(),
              composition(one),
              one.occupationOnly() ? "（有一部分按占用区间）" : ""));
    }
    CapacityReport.Bottleneck top = bottlenecks.get(0);
    StringBuilder verdict = new StringBuilder("  最紧的是 ").append(resourceLabel(top)).append("：");
    if (top.utilization() > 1.0D) {
      verdict.append("超过 100%，表上已有车次的闭塞时间重叠，运行时会在这里减速或等待");
    } else {
      verdict.append(String.format(Locale.ROOT, "所有间隔最多压到现在的 %.0f%%", top.utilization() * 100.0D));
    }
    if (bottlenecks.size() > 1 && top.passes() > 0) {
      CapacityReport.Bottleneck next = bottlenecks.get(1);
      double perPass =
          (top.utilization() - next.utilization()) * CapacityReport.WINDOW_SECONDS / top.passes();
      verdict.append(
          String.format(
              Locale.ROOT,
              "；要降到第二紧（%s，%.0f%%），每次得少占 %.0fs",
              resourceLabel(next),
              next.utilization() * 100.0D,
              perPass));
    }
    out.add(verdict.toString());
    if (report.unplaced() > 0) {
      out.add("  另有 " + report.unplaced() + " 段动态站台停留定不下股道（没有计划股道），没有计入");
    }
    return out;
  }

  /** 资源键换成"种类 名称"：区间写两端节点。 */
  static String resourceLabel(CapacityReport.Bottleneck one) {
    String key = one.key();
    int colon = key.indexOf(':');
    String name = colon < 0 ? key : key.substring(colon + 1);
    if (key.startsWith("edge:")) {
      name = name.replace("~", " – ");
    }
    return describe(one.kind()) + " " + name;
  }

  /** 每次占用的组成，只列不为零的几项。 */
  private static String composition(CapacityReport.Bottleneck one) {
    List<String> parts = new ArrayList<>();
    addPart(parts, "进入前", one.approachSeconds());
    addPart(
        parts,
        one.kind() == TimetableConflictChecker.Kind.PLATFORM ? "占用（含停站）" : "占用",
        one.occupySeconds());
    addPart(parts, "出清", one.clearSeconds());
    addPart(parts, "折返与待命", one.standSeconds());
    return String.join(" + ", parts);
  }

  private static void addPart(List<String> parts, String label, double seconds) {
    if (seconds >= 0.5D) {
      parts.add(String.format(Locale.ROOT, "%s %.0f", label, seconds));
    }
  }

  /** 从端点占用成本里反推本端点用的折返秒数：成本 = 进站 + 折返 + 出站 + 裕量。 */
  private static int turnaroundOf(TerminalSerializer.TerminalReport terminal) {
    return Math.max(
        0,
        terminal.visitCostSeconds()
            - terminal.approachInSeconds()
            - terminal.approachOutSeconds()
            - terminal.separationSeconds());
  }

  /**
   * 搜索失败的文案：端点利用率超过 100% 时说"结构上不可能"，否则说"范围内没找到"并点名剩余冲突最多的资源。
   * 两种情况下运营者要做的事不同：前者改折返/权重/股道，后者改裕量或等下一轮把咽喉也串行。
   */
  public static String describeSearchFailure(
      int targetHeadway,
      int limitHeadway,
      TimetableConflictChecker.Report conflictsAtTarget,
      List<TerminalSerializer.TerminalReport> terminals,
      TurnaroundTable turnarounds) {
    for (TerminalSerializer.TerminalReport terminal : terminals) {
      if (terminal.utilization() > 1.0D) {
        return String.format(
            Locale.ROOT,
            "目标间隔 %ds 下端点 %s（单股道）利用率 %.0f%%（每次折返占用 %ds，折返取 %s）：目标本身在结构上不可能；放宽到 %ds 仍找不到无冲突的间隔。可做的事：核对终到站的 dwell 是否偏大或 --turnaround 是否设得太长、降低经过该端点的 route 权重、或增加股道",
            targetHeadway,
            terminal.group(),
            terminal.utilization() * 100.0D,
            terminal.visitCostSeconds(),
            turnarounds.describe(),
            limitHeadway);
      }
    }
    return "目标间隔 "
        + targetHeadway
        + "s 在放宽到 "
        + limitHeadway
        + "s 的范围内没有找到无冲突的间隔；剩余冲突集中在 "
        + String.join("、", conflictsAtTarget.topResources(3))
        + "。检查单线区段、站台数量与折返时间";
  }
}
