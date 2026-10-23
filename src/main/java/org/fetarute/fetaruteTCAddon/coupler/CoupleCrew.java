package org.fetarute.fetaruteTCAddon.coupler;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 联挂时两车上值乘的人（驾驶员、车掌），由驾驶模块接入；没有接入时当作没人值乘。
 *
 * <p>进站与挂车由调度控车：开始进站前，后到的车上的驾驶员把这一趟在这里结算、让出控制；回程支线车挂上后身份作废，车上的驾驶员与车掌也在这时结算、结束值乘。
 * 挂上后合并的列车没有驾驶员时，把让出控制的驾驶员送回合并列车发车端的驾驶室接着驾驶；主干原有的驾驶员、车掌照常值乘，落在列车中间时换到车头、车尾。
 */
public interface CoupleCrew {

  /** 没有接入驾驶模块。 */
  CoupleCrew NONE =
      new CoupleCrew() {
        @Override
        public void yieldBeforeApproach(Approach approach) {}

        @Override
        public void coupled(Approach approach) {}

        @Override
        public void notCoupled(Approach approach) {}
      };

  /** 开始进站前：让出控制、结束要作废的一方上的值乘（见类说明）。须同步办完，之后后到的车上不再有驾驶员。 */
  void yieldBeforeApproach(Approach approach);

  /** 挂上了（合并的列车沿用主干的车名）。 */
  void coupled(Approach approach);

  /** 进站没成、没挂上：让出控制的驾驶员不再送回。 */
  void notCoupled(Approach approach);

  /**
   * 一次联挂进站。
   *
   * @param trunkName 主干（挂上后合并的列车沿用它的车名）
   * @param feederName 回程支线车（挂上后身份作废）
   * @param moverName 后到、开进站的一方（主干或回程支线车）
   */
  record Approach(String trunkName, String feederName, String moverName) {

    public Approach {
      Objects.requireNonNull(trunkName, "trunkName");
      Objects.requireNonNull(feederName, "feederName");
      Objects.requireNonNull(moverName, "moverName");
    }

    /** 后到的是主干。 */
    public boolean trunkMoves() {
      return moverName.equalsIgnoreCase(trunkName);
    }
  }

  /** 合起几方值乘的人；每次调用时再取各方此刻的实现（驾驶模块会重载）。 */
  static CoupleCrew all(List<Supplier<CoupleCrew>> parts) {
    List<Supplier<CoupleCrew>> copy = List.copyOf(parts);
    return new CoupleCrew() {
      @Override
      public void yieldBeforeApproach(Approach approach) {
        copy.forEach(part -> part.get().yieldBeforeApproach(approach));
      }

      @Override
      public void coupled(Approach approach) {
        copy.forEach(part -> part.get().coupled(approach));
      }

      @Override
      public void notCoupled(Approach approach) {
        copy.forEach(part -> part.get().notCoupled(approach));
      }
    };
  }
}
