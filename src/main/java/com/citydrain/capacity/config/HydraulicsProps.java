package com.citydrain.capacity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缺省扩大区间假设。所有假设在计算时写入覆盖账本与证据，可逐项复核。
 */
@ConfigurationProperties(prefix = "app.hydraulics")
public record HydraulicsProps(
        double defaultRoughnessMin,
        double defaultRoughnessMax,
        double defaultSiltMax,
        double assumedSurchargeM,
        long alignmentToleranceSeconds
) {}
