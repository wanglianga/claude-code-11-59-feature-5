package com.citydrain.capacity.service.eval;

import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.ParamVersionRepo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路线A：工程水力公式逐段区间传播。
 * 每段独立解析：参数版本声明区间 + 该段下游节点水位观测区间（DOWNSTREAM_LEVEL 优先，
 * 缺省 WATER_LEVEL）。不做段间联合传播——结果稳定、易逐项复核。
 */
@Component
public class IntervalPropagationEvaluator {

    private final ParamVersionRepo paramRepo;
    private final ObservationRepo obsRepo;
    private final ExternalRefRepo extRepo;
    private final SegmentIntervalMath math;

    public IntervalPropagationEvaluator(ParamVersionRepo paramRepo, ObservationRepo obsRepo,
                                        ExternalRefRepo extRepo, SegmentIntervalMath math) {
        this.paramRepo = paramRepo;
        this.obsRepo = obsRepo;
        this.extRepo = extRepo;
        this.math = math;
    }

    public SegmentEvaluation evaluateSegment(Segment seg, EvalContext ctx) {
        ParamVersion pv = paramRepo.resolveAt(seg.id(), ctx.asOf()).orElse(null);

        // 逐段尾水：该段下游节点的事件窗口内水位观测（as_of 前入库）
        List<Observation> levelObs = obsRepo.forComputation(
                Vocab.OBS_DOWNSTREAM_LEVEL, seg.toNode(),
                ctx.windowStart(), ctx.windowEnd(), ctx.asOf());
        if (levelObs.isEmpty()) {
            levelObs = obsRepo.forComputation(
                    Vocab.OBS_WATER_LEVEL, seg.toNode(),
                    ctx.windowStart(), ctx.windowEnd(), ctx.asOf());
        }
        double[] tw = null;
        List<Long> twObsIds = List.of();
        if (!levelObs.isEmpty()) {
            double min = levelObs.stream().mapToDouble(Observation::valueNum).min().orElseThrow();
            double max = levelObs.stream().mapToDouble(Observation::valueNum).max().orElseThrow();
            tw = new double[]{min, max};
            twObsIds = levelObs.stream().map(Observation::id).toList();
        }

        List<ExternalRef> defects = extRepo.listBySegmentAndType(seg.id(), Vocab.REF_DEFECT);
        List<ExternalRef> inspections = extRepo.listBySegmentAndType(seg.id(), Vocab.REF_INSPECTION);

        return math.evaluate(seg, pv, tw, twObsIds, null, ctx.gateFor(seg.segmentCode()),
                defects, inspections, null);
    }
}
