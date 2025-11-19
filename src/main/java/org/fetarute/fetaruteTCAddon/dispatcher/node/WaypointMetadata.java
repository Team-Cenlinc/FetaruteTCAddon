package org.fetarute.fetaruteTCAddon.dispatcher.node;

import java.util.Objects;
import java.util.Optional;

/**
 * Waypoint（包括站咽喉、Depot throat 等）的编码信息。
 * 解析自 SignAction 文本，便于调度层按照运营商/区间/股道规则处理。
 */
public record WaypointMetadata(
        String operator,
        String originStation,
        Optional<String> destinationStation,
        int trackNumber,
        String sequence,
        WaypointKind kind
) {

    public WaypointMetadata {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(originStation, "originStation");
        Objects.requireNonNull(destinationStation, "destinationStation");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(kind, "kind");
        if (trackNumber < 0) {
            throw new IllegalArgumentException("trackNumber 不能为负数");
        }
    }

    public static WaypointMetadata interval(
            String operator,
            String origin,
            String destination,
            int trackNumber,
            String sequence
    ) {
        return new WaypointMetadata(
                operator,
                origin,
                Optional.ofNullable(destination),
                trackNumber,
                sequence,
                WaypointKind.INTERVAL
        );
    }

    public static WaypointMetadata throat(
            String operator,
            String station,
            int trackNumber,
            String sequence
    ) {
        return new WaypointMetadata(
                operator,
                station,
                Optional.empty(),
                trackNumber,
                sequence,
                WaypointKind.STATION_THROAT
        );
    }

    public static WaypointMetadata depot(
            String operator,
            String depotId,
            int trackNumber,
            String sequence
    ) {
        return new WaypointMetadata(
                operator,
                depotId,
                Optional.empty(),
                trackNumber,
                sequence,
                WaypointKind.DEPOT
        );
    }
}
