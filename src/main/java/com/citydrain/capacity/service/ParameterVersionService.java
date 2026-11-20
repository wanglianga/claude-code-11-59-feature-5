package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 资产参数库服务：版本登记、窗口关闭、历史时点解析。 */
@Service
public class ParameterVersionService {

    private final ParamVersionRepo paramRepo;
    private final SegmentRepo segmentRepo;

    public ParameterVersionService(ParamVersionRepo paramRepo, SegmentRepo segmentRepo) {
        this.paramRepo = paramRepo;
        this.segmentRepo = segmentRepo;
    }

    /**
     * 登记经确认的新参数版本：自动递增版本号，并在 validFrom 时刻关闭上一打开版本。
     * 旧版本保留（不抹去历史测量）。
     */
    @Transactional
    public ParamVersion createVersion(long segmentId, String source,
                                      Double diameterMinMm, Double diameterMaxMm,
                                      Double slopeMin, Double slopeMax,
                                      Double roughnessMin, Double roughnessMax,
                                      Double siltRatioMin, Double siltRatioMax,
                                      OffsetDateTime validFrom, String createdBy, String note) {
        segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + segmentId));
        Optional<ParamVersion> open = paramRepo.findOpenVersion(segmentId);
        if (open.isPresent() && !validFrom.isAfter(open.get().validFrom())) {
            throw ApiException.unprocessable("新版本 validFrom 必须晚于上一版本 validFrom "
                    + open.get().validFrom());
        }
        int versionNo = paramRepo.nextVersionNo(segmentId);
        ParamVersion v = new ParamVersion(0, segmentId, versionNo, source,
                diameterMinMm, diameterMaxMm, slopeMin, slopeMax,
                roughnessMin, roughnessMax, siltRatioMin, siltRatioMax,
                validFrom, null, OffsetDateTime.now(), createdBy, null, note);
        long id = paramRepo.insert(v);
        open.ifPresent(prev -> paramRepo.closeVersion(prev.id(), validFrom));
        return paramRepo.findById(id).orElseThrow();
    }

    public List<ParamVersion> listBySegment(long segmentId) {
        return paramRepo.listBySegment(segmentId);
    }

    public Optional<ParamVersion> resolveAt(long segmentId, OffsetDateTime asOf) {
        return paramRepo.resolveAt(segmentId, asOf);
    }
}
