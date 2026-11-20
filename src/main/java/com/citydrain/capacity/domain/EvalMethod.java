package com.citydrain.capacity.domain;

/** 评估方法：两条独立路线，互不作为对方的预处理或核验。 */
public enum EvalMethod {
    /** 工程水力公式逐段区间传播 */
    INTERVAL_PROPAGATION,
    /** 经已知工况校准的水力情景集合（多段联合、回水联动） */
    CALIBRATED_ENSEMBLE
}
