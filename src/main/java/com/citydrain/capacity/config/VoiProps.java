package com.citydrain.capacity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 复检信息价值估计参数（均可在 application.yml 复核调整）。
 * 两条估计路线共用同一组参数与同一 evi 口径，保证结果可对照。
 */
@Component
@ConfigurationProperties(prefix = "app.voi")
public class VoiProps {

    /** 风险区间视为 ±2σ（95% 覆盖）→ σ = 区间宽度 / intervalSigmaDivisor。 */
    private double intervalSigmaDivisor = 4.0;
    /** 证据冲突时先验 σ 放大系数。 */
    private double conflictInflation = 1.25;
    /** 低价值判定：决策变化概率低于该值且信息增益有限 → 标记低价值。 */
    private double lowValueDecisionProb = 0.05;
    /** 测量不确定度基准（风险分），按候选分辨率/定位误差缩放。 */
    private double measBaseRiskPoints = 3.0;
    /** 参考方法分辨率（米）：CCTV 约 5mm。 */
    private double referenceResolutionM = 0.005;
    /** 参考定位误差（米）。 */
    private double referenceLocationErrorM = 1.0;
    /** 敏感度法局部窗口：仅考虑距当前决定带边界 ±该倍数 σ 内的阈值跳变。 */
    private double sensitivityLocalWindowStd = 1.5;
    /** AUTO 选路：候选数不超过该值用枚举法，超过用敏感度法。 */
    private int autoEnumerationMaxCandidates = 12;
    /** 发现已修复结果对应的后验风险分基准。 */
    private double repairedRiskScore = 10.0;
    /** evi 合成权重：决策变化 / 剩余风险长度 / 网络影响结论。 */
    private Weight weight = new Weight();

    public static class Weight {
        private double decision = 0.60;
        private double riskLength = 0.25;
        private double network = 0.15;

        public double getDecision() { return decision; }
        public void setDecision(double decision) { this.decision = decision; }
        public double getRiskLength() { return riskLength; }
        public void setRiskLength(double riskLength) { this.riskLength = riskLength; }
        public double getNetwork() { return network; }
        public void setNetwork(double network) { this.network = network; }
    }

    public double getIntervalSigmaDivisor() { return intervalSigmaDivisor; }
    public void setIntervalSigmaDivisor(double v) { this.intervalSigmaDivisor = v; }
    public double getConflictInflation() { return conflictInflation; }
    public void setConflictInflation(double v) { this.conflictInflation = v; }
    public double getLowValueDecisionProb() { return lowValueDecisionProb; }
    public void setLowValueDecisionProb(double v) { this.lowValueDecisionProb = v; }
    public double getMeasBaseRiskPoints() { return measBaseRiskPoints; }
    public void setMeasBaseRiskPoints(double v) { this.measBaseRiskPoints = v; }
    public double getReferenceResolutionM() { return referenceResolutionM; }
    public void setReferenceResolutionM(double v) { this.referenceResolutionM = v; }
    public double getReferenceLocationErrorM() { return referenceLocationErrorM; }
    public void setReferenceLocationErrorM(double v) { this.referenceLocationErrorM = v; }
    public double getSensitivityLocalWindowStd() { return sensitivityLocalWindowStd; }
    public void setSensitivityLocalWindowStd(double v) { this.sensitivityLocalWindowStd = v; }
    public int getAutoEnumerationMaxCandidates() { return autoEnumerationMaxCandidates; }
    public void setAutoEnumerationMaxCandidates(int v) { this.autoEnumerationMaxCandidates = v; }
    public double getRepairedRiskScore() { return repairedRiskScore; }
    public void setRepairedRiskScore(double v) { this.repairedRiskScore = v; }
    public Weight getWeight() { return weight; }
    public void setWeight(Weight weight) { this.weight = weight; }
}
