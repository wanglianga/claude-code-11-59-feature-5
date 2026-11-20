package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.config.VoiProps;

/**
 * VOI 估计纯数学核心（确定性、可逐项复核），两条估计路线共用：
 * 先验风险分布、测量后验、阈值带决策与离散结果概率。
 * 风险分截断于 [0,100]；区间按 ±2σ 解释（95% 覆盖，σ=区间宽/divisor）。
 */
public final class VoiMath {

    private VoiMath() {}

    /** Abramowitz-Stegun 7.1.26 误差函数近似（|误差| < 1.5e-7）。 */
    public static double erf(double x) {
        double sign = Math.signum(x);
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }

    /** 标准正态累积分布。 */
    public static double phi(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /** 标准正态概率密度。 */
    public static double pdf(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI);
    }

    /** 正态分布 N(mean,sigma) 在 x 处的 CDF。 */
    public static double normalCdf(double x, double mean, double sigma) {
        if (sigma <= 0) return x >= mean ? 1.0 : 0.0;
        return phi((x - mean) / sigma);
    }

    /**
     * 截断正态条件期望 E[X | a &lt; X ≤ b]，X ~ N(mean,sigma)；
     * a 传 Double.NEGATIVE_INFINITY、b 传 POSITIVE_INFINITY 表示无界。
     */
    public static double truncatedMean(double mean, double sigma, double a, double b) {
        double alpha = Double.isInfinite(a) ? Double.NEGATIVE_INFINITY : (a - mean) / sigma;
        double beta = Double.isInfinite(b) ? Double.POSITIVE_INFINITY : (b - mean) / sigma;
        double pA = Double.isInfinite(a) ? 0.0 : phi(alpha);
        double pB = Double.isInfinite(b) ? 1.0 : phi(beta);
        double denom = Math.max(pB - pA, 1e-12);
        double pdfA = Double.isInfinite(a) ? 0.0 : pdf(alpha);
        double pdfB = Double.isInfinite(b) ? 0.0 : pdf(beta);
        return mean + sigma * (pdfA - pdfB) / denom;
    }

    /** P(a &lt; X ≤ b)，无界参数同 {@link #truncatedMean}。 */
    public static double normalProbability(double mean, double sigma, double a, double b) {
        double pA = Double.isInfinite(a) ? 0.0 : normalCdf(a, mean, sigma);
        double pB = Double.isInfinite(b) ? 1.0 : normalCdf(b, mean, sigma);
        return Math.max(0.0, pB - pA);
    }

    /** 先验：风险区间 [min,max] → 截断正态参数（中点与 σ），证据冲突时放大 σ。 */
    public static double[] prior(double riskMin, double riskMax, boolean conflict, VoiProps props) {
        double mean = (riskMin + riskMax) / 2.0;
        double sigma = Math.max(1e-6, (riskMax - riskMin) / props.getIntervalSigmaDivisor());
        if (conflict) {
            sigma *= props.getConflictInflation();
        }
        return new double[]{clamp(mean, 0, 100), sigma};
    }

    /**
     * 测量不确定度 σ_meas：方法分辨率/定位误差越差、定位模糊越大，复检能提供的信息越少。
     * measurementFactor ∈ (0,1]：1 = 与参考方法同等能力。
     */
    public static double measurementSigma(double measurementFactor, double ambiguityM, VoiProps props) {
        double f = clamp(measurementFactor, 0.05, 1.0);
        double fromMethod = props.getMeasBaseRiskPoints() / f;
        double fromAmbiguity = ambiguityM * 0.5; // 每米定位模糊折算 0.5 个风险分
        return Math.sqrt(fromMethod * fromMethod + fromAmbiguity * fromAmbiguity);
    }

    /** 贝叶斯共轭后验 σ（正态-正态）：1/σ_post² = 1/σ² + 1/σ_meas²。 */
    public static double posteriorSigma(double priorSigma, double measSigma) {
        return 1.0 / Math.sqrt(1.0 / (priorSigma * priorSigma) + 1.0 / (measSigma * measSigma));
    }

    /** 后验均值：测量值 m 对先验均值的收缩。 */
    public static double posteriorMean(double priorMean, double priorSigma,
                                       double measuredMean, double measSigma) {
        double vp = priorSigma * priorSigma;
        double vm = measSigma * measSigma;
        return (priorMean * vm + measuredMean * vp) / (vp + vm);
    }

    /**
     * 联合高斯下“测得更严重”的概率：测量 M ~ N(θ,σ_meas)、θ ~ N(μ,σ)，
     * 事件 M > μ + z·σ_meas 的边际概率（M 边际分布 N(μ, σ²+σ_meas²)）。
     */
    public static double probabilityWorse(double priorMean, double priorSigma,
                                          double measSigma, double z) {
        double marginalSigma = Math.sqrt(priorSigma * priorSigma + measSigma * measSigma);
        return 1.0 - normalCdf(priorMean + z * measSigma, priorMean, marginalSigma);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 决策阈值带：riskScore ≥ remediateMin → REMEDIATE；≥ monitorMin → MONITOR；否则 NO_ACTION。 */
    public record Thresholds(double monitorMin, double remediateMin,
                             double remediationMargin, double remainingRiskLps) {
        public String decisionOf(double riskScore) {
            if (riskScore >= remediateMin) return "REMEDIATE";
            if (riskScore >= monitorMin) return "MONITOR";
            return "NO_ACTION";
        }
    }
}
