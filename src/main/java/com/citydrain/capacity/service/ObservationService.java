package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.repo.ObservationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** 运行观测库服务：批量入库（ingested_at 取服务器时间，支撑迟到补录语义）与查询。 */
@Service
public class ObservationService {

    private static final Set<String> OBS_TYPES = Set.of(
            "FLOW", "WATER_LEVEL", "RAINFALL", "DOWNSTREAM_LEVEL", "GATE_STATE");
    private static final Set<String> TARGET_TYPES = Set.of("SEGMENT", "NODE", "GATE", "BOUNDARY");

    private final ObservationRepo obsRepo;

    public ObservationService(ObservationRepo obsRepo) {
        this.obsRepo = obsRepo;
    }

    @Transactional
    public List<Long> ingest(List<Observation> observations) {
        for (Observation o : observations) {
            if (!OBS_TYPES.contains(o.obsType())) {
                throw ApiException.badRequest("非法 obsType: " + o.obsType());
            }
            if (!TARGET_TYPES.contains(o.targetType())) {
                throw ApiException.badRequest("非法 targetType: " + o.targetType());
            }
            if ("GATE_STATE".equals(o.obsType())) {
                if (o.valueText() == null
                        || !Set.of("OPEN", "CLOSED", "PARTIAL").contains(o.valueText().toUpperCase())) {
                    throw ApiException.badRequest("GATE_STATE 需要 valueText ∈ {OPEN, CLOSED, PARTIAL}");
                }
            } else if (o.valueNum() == null) {
                throw ApiException.badRequest(o.obsType() + " 需要 valueNum");
            }
        }
        return observations.stream().map(obsRepo::insert).toList();
    }

    public List<Observation> query(String obsType, String targetCode,
                                   OffsetDateTime from, OffsetDateTime to, int limit) {
        return obsRepo.query(obsType, targetCode, from, to, Math.min(limit, 5000));
    }
}
