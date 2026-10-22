package org.fetarute.fetaruteTCAddon.coupler;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂上以后整列有多长：先到的车停在哪里，整列的中点（车头与车尾车厢中心的中点）才对准车站牌子。纯计算。
 *
 * <p>相邻两节车挂着时中心之间的距离与 TrainCarts 一致：各自半车长加各自车钩长（见 {@link CarEnds#coupledDistance}）。
 */
final class JoinSpan {

  /**
   * 一节车厢的尺寸。
   *
   * @param halfLength 半车长（格）
   * @param couplerLength 车钩长度（格）
   */
  record CarSize(double halfLength, double couplerLength) {}

  private JoinSpan() {}

  /**
   * 先到那列的第一节车厢中心要停在整列中点前方多少格。
   *
   * @param own 先到那列的各节车厢，从车头起
   * @param partner 挂上来那列的各节车厢，从挂上的那一端起；还没出车时为空
   * @param partnerCars 挂上来那列几节（{@code partner} 为空时按先到那列的最后一节估）
   * @return 量不出时为 {@code NaN}
   */
  static double midpointLead(List<CarSize> own, List<CarSize> partner, int partnerCars) {
    if (own.isEmpty() || (partner.isEmpty() && partnerCars <= 0)) {
      return Double.NaN;
    }
    List<CarSize> train = new ArrayList<>(own);
    if (partner.isEmpty()) {
      CarSize last = own.get(own.size() - 1);
      for (int i = 0; i < partnerCars; i++) {
        train.add(last);
      }
    } else {
      train.addAll(partner);
    }
    double span = 0.0;
    for (int i = 1; i < train.size(); i++) {
      CarSize a = train.get(i - 1);
      CarSize b = train.get(i);
      span += a.halfLength() + a.couplerLength() + b.halfLength() + b.couplerLength();
    }
    return span > 0.0 && Double.isFinite(span) ? span / 2.0 : Double.NaN;
  }
}
