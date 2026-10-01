package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * HUD 双语轮播：BossBar、ActionBar 与车内 LCD 在同一时刻显示同一种语言。
 *
 * <p>三块显示若各自按自己的计数器、行数和周期轮播：计数器起点不同、某个状态的行数不同（如 DEPARTING 两行中文一行英文）、 各显示周期不同，
 * 都会让它们一块中文一块英文。因此统一三件事：
 *
 * <ul>
 *   <li><b>同一个时钟</b>：{@link #nowTicks()} 取墙钟换算的 tick，与各显示的刷新间隔、启动先后、重载都无关。
 *   <li><b>同一个语言周期</b>：中英文切换周期是全局配置 {@code runtime.hud.language-rotate-ticks}，不取各模板自己的周期——
 *       管理员改过的模板、线路绑定模板不会随插件升级更新，只改默认模板对它们不生效。
 *   <li><b>先定语言再轮播</b>：同一语言有多行时，每轮到这种语言换下一行。 语言由行内容推断，不需要改模板。
 * </ul>
 *
 * <p>只有一种语言的状态（或整份模板都没有可识别的语言）仍按模板自己的周期轮播，行为不变。
 */
public final class HudLanguageRotation {

  private static final Pattern MINI_MESSAGE_TAG = Pattern.compile("<[^<>]*>");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

  /** 默认中英文切换周期（ticks）。 */
  public static final long DEFAULT_LANGUAGE_PERIOD_TICKS = 60L;

  private static volatile long languagePeriodTicks = DEFAULT_LANGUAGE_PERIOD_TICKS;

  private HudLanguageRotation() {}

  /** 设置全局中英文切换周期（由显示服务按配置设置）；非正值回到默认。 */
  public static void setLanguagePeriodTicks(long ticks) {
    languagePeriodTicks = ticks > 0L ? ticks : DEFAULT_LANGUAGE_PERIOD_TICKS;
  }

  /** 当前全局中英文切换周期。 */
  public static long languagePeriodTicks() {
    return languagePeriodTicks;
  }

  /** 行的语言归属。 */
  public enum Language {
    /** 第一语言（含汉字）。 */
    PRIMARY,
    /** 第二语言（只有拉丁字母，或引用了 {@code _lang2}/{@code en_US} 占位符）。 */
    SECONDARY,
    /** 与语言无关（只有符号与通用占位符），两种语言相位下都可显示。 */
    NEUTRAL
  }

  /** 所有 HUD 共用的轮播时钟（tick，1 tick = 50ms）。 */
  public static long nowTicks() {
    return System.currentTimeMillis() / 50L;
  }

  /**
   * 推断一行模板文本的语言。
   *
   * <p>先去掉 MiniMessage 标签与占位符再看剩下的文字：有汉字即第一语言，只有拉丁字母即第二语言。 都没有时看占位符名：{@code _lang2}/{@code en_US}
   * 归第二语言，{@code zh_CN} 归第一语言，其余视为通用。
   */
  public static Language classify(String text) {
    if (text == null || text.isBlank()) {
      return Language.NEUTRAL;
    }
    String withoutTags = MINI_MESSAGE_TAG.matcher(text).replaceAll(" ");
    String bare = PLACEHOLDER.matcher(withoutTags).replaceAll(" ");
    boolean latin = false;
    for (int i = 0; i < bare.length(); ) {
      int cp = bare.codePointAt(i);
      if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
        return Language.PRIMARY;
      }
      if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
        latin = true;
      }
      i += Character.charCount(cp);
    }
    if (latin) {
      return Language.SECONDARY;
    }
    var matcher = PLACEHOLDER.matcher(withoutTags);
    boolean primaryHint = false;
    while (matcher.find()) {
      String name = matcher.group(1).toLowerCase(Locale.ROOT);
      if (name.endsWith("_lang2") || name.endsWith("en_us")) {
        return Language.SECONDARY;
      }
      if (name.endsWith("zh_cn")) {
        primaryHint = true;
      }
    }
    return primaryHint ? Language.PRIMARY : Language.NEUTRAL;
  }

  /**
   * 按“先语言、后行序”从候选中选出当前展示项，语言周期取全局配置。
   *
   * @param items 候选（已按模板顺序排好）
   * @param language 每个候选的语言
   * @param tick 共享时钟（{@link #nowTicks()}）
   * @param periodTicks 模板自己的轮播周期（只用于单语状态）
   * @return 当前展示项；候选为空时为 null
   */
  public static <T> T select(
      List<T> items, Function<T, Language> language, long tick, long periodTicks) {
    return select(items, language, tick, periodTicks, languagePeriodTicks);
  }

  /**
   * 同上，显式给出语言周期。
   *
   * @param languagePeriod 中英文切换周期（双语状态）
   */
  public static <T> T select(
      List<T> items,
      Function<T, Language> language,
      long tick,
      long periodTicks,
      long languagePeriod) {
    if (items == null || items.isEmpty()) {
      return null;
    }
    if (items.size() == 1) {
      return items.get(0);
    }
    long safeTick = Math.max(0L, tick);
    List<T> primary = new ArrayList<>();
    List<T> secondary = new ArrayList<>();
    for (T item : items) {
      Language lang = language.apply(item);
      if (lang == Language.PRIMARY) {
        primary.add(item);
      } else if (lang == Language.SECONDARY) {
        secondary.add(item);
      } else {
        primary.add(item);
        secondary.add(item);
      }
    }
    boolean bilingual =
        items.stream().anyMatch(item -> language.apply(item) == Language.PRIMARY)
            && items.stream().anyMatch(item -> language.apply(item) == Language.SECONDARY);
    if (!bilingual) {
      return items.get((int) ((safeTick / Math.max(1L, periodTicks)) % items.size()));
    }
    long slot = safeTick / Math.max(1L, languagePeriod);
    List<T> group = slot % 2L == 0L ? primary : secondary;
    return group.get((int) ((slot / 2L) % group.size()));
  }
}
