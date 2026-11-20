package com.citydrain.capacity.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI drainCapacityOpenAPI() {
        String scheme = "basicAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("城市地下管网输送能力区间评估与运行分析平台")
                        .version("1.0.0")
                        .description("""
                                资产参数库（经确认的几何/坡度/糙率/淤积区间及有效版本）+ 运行观测库
                                （流量/水位/降雨/下游边界/闸门状态）+ 外部只读引用（缺陷/检查覆盖/维修）。
                                能力评估器对每段输出可通过流量区间、相对设计需求余量、主导不确定参数与
                                不可判定原因；覆盖账本记录每次计算的参数与观测可用性；结果可按历史时点
                                复算并供下游网络情景查询。两条评估路线（区间传播 / 校准情景集合）相互独立。
                                复检信息价值（VOI）：对候选复检方案以结果枚举法或局部敏感度法估计预期信息价值，
                                输出对整治排序/剩余风险长度/网络影响结论可缩小的范围与低价值检查，并在
                                预算/供应商/雨季约束下生成可留痕的复检计划版本。
                                """))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
