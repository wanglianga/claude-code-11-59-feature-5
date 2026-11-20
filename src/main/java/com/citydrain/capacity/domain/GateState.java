package com.citydrain.capacity.domain;

/** 闸门状态。冲突时拆分条件情景分别计算，不取平均。 */
public enum GateState {
    OPEN,
    CLOSED,
    PARTIAL;

    public static GateState parse(String text) {
        if (text == null) return null;
        try {
            return GateState.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
