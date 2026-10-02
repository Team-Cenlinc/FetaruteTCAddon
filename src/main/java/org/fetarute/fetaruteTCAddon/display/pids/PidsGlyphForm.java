package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.Locale;
import java.util.Optional;

/**
 * 中日文字形版本。
 *
 * <p>同一个汉字在简体、繁体、日文里的写法不同（如“骨”“直”），内置像素字体按地区各备一套字形。站台屏的中文行用这里选定的版本，英文行固定用拉丁版。
 */
public enum PidsGlyphForm {
  /** 简体中文。 */
  ZH_HANS("zh_hans"),
  /** 繁体中文。 */
  ZH_HANT("zh_hant"),
  /** 日文。 */
  JA("ja");

  private final String fileSuffix;

  PidsGlyphForm(String fileSuffix) {
    this.fileSuffix = fileSuffix;
  }

  /** 内置字体文件名中的版本后缀，也是配置取值。 */
  public String fileSuffix() {
    return fileSuffix;
  }

  /**
   * 按配置取值解析，忽略大小写，并容忍用连字符代替下划线。
   *
   * @param raw 配置中的原始文本
   * @return 对应版本；无法识别时为空
   */
  public static Optional<PidsGlyphForm> fromConfig(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    for (PidsGlyphForm form : values()) {
      if (form.fileSuffix.equals(normalized)) {
        return Optional.of(form);
      }
    }
    return Optional.empty();
  }
}
