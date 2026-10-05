package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 节点牌子的显示类型名：站咽喉、车库咽喉等按 WaypointKind 细分，其余按 NodeType。 */
public final class SignNodeTypeNames {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private SignNodeTypeNames() {}

  /**
   * 本地化后的纯文本类型名。
   *
   * <p>语言文件中的 sign.type.* 是文本值而不是语言键，因此先解析为组件再转为纯文本，供占位符填入。
   */
  public static String localized(LocaleManager locale, SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    if (locale == null) {
      return definition.nodeType().name();
    }
    return PLAIN_TEXT.serialize(locale.component(key(definition)));
  }

  static String key(SignNodeDefinition definition) {
    WaypointKind kind = definition.waypointMetadata().map(WaypointMetadata::kind).orElse(null);
    if (kind == WaypointKind.STATION_THROAT) {
      return "sign.type.station_throat";
    }
    if (kind == WaypointKind.DEPOT_THROAT) {
      return "sign.type.depot_throat";
    }
    if (kind == WaypointKind.STATION) {
      return "sign.type.station";
    }
    if (kind == WaypointKind.DEPOT) {
      return "sign.type.depot";
    }
    return "sign.type." + definition.nodeType().name().toLowerCase(Locale.ROOT);
  }
}
