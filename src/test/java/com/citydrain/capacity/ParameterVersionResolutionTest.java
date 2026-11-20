package com.citydrain.capacity;

import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.service.ApiException;
import com.citydrain.capacity.service.ExternalRefService;
import com.citydrain.capacity.service.ParameterVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static com.citydrain.capacity.TestFixtures.t;
import static org.junit.jupiter.api.Assertions.*;

/** 参数版本解析：有效窗 + 知识截止；不得以最新值回填历史。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ParameterVersionResolutionTest {

    @Autowired TestFixtures fx;
    @Autowired ParamVersionRepo paramRepo;
    @Autowired ParameterVersionService paramService;
    @Autowired ExternalRefService extService;

    @BeforeEach
    void setup() {
        fx.baseNetwork();
    }

    @Test
    void resolvesVersionCoveringAsOf() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        Optional<ParamVersion> v = paramRepo.resolveAt(fx.segA, t(2026, 7, 20, 0, 0));
        assertTrue(v.isPresent());
        assertEquals(1, v.get().versionNo());
    }

    @Test
    void versionCreatedAfterAsOfIsInvisible() {
        // 版本 6 月才登记 → 3 月的复算不可见
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 6, 1, 10, 0));
        assertTrue(paramRepo.resolveAt(fx.segA, t(2026, 3, 1, 0, 0)).isEmpty(),
                "登记时间晚于 as_of → 不可见");
        assertTrue(paramRepo.resolveAt(fx.segA, t(2026, 7, 1, 0, 0)).isPresent());
    }

    @Test
    void noCoveringVersionReturnsEmptyNotLatest() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 6, 1, 0, 0), t(2026, 1, 5, 10, 0));
        // 有效窗 6 月开始 → 3 月无版本，不得回填
        assertTrue(paramRepo.resolveAt(fx.segA, t(2026, 3, 1, 0, 0)).isEmpty());
    }

    @Test
    void newVersionClosesPreviousAndKeepsOld() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        paramService.createVersion(fx.segA, "SURVEY", 995.0, 1000.0, 0.0019, 0.0021,
                0.012, 0.014, 0.0, 0.05, t(2026, 8, 1, 0, 0), "test", "复测");
        var all = paramRepo.listBySegment(fx.segA);
        assertEquals(2, all.size(), "旧版本保留");
        assertNotNull(all.get(0).validTo(), "v1 已关闭");
        assertNull(all.get(1).validTo(), "v2 打开");
        // 7 月 → v1；9 月 → v1 已关闭、v2 登记于"现在"（知识截止）→ 空窗不可判定；现在 → v2
        assertEquals(1, paramRepo.resolveAt(fx.segA, t(2026, 7, 20, 0, 0)).orElseThrow().versionNo());
        assertTrue(paramRepo.resolveAt(fx.segA, t(2026, 9, 20, 0, 0)).isEmpty(),
                "v2 登记晚于该时点 → 知识空窗，不回填");
        assertEquals(2, paramRepo.resolveAt(fx.segA, OffsetDateTime.now().plusMinutes(1))
                .orElseThrow().versionNo());
    }

    @Test
    void repairDerivationRequiresAcceptanceAndTraceability() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        // 无验收
        long pending = fx.extRepo.insert("REPAIR_ACTION", "R-P", "维修系统", "v1", fx.segA,
                t(2026, 9, 1, 9, 0),
                "{\"acceptance\":{\"result\":\"PENDING\"},\"measuredChanges\":{\"diameterMm\":[995,1000]},\"measurementRef\":\"M1\"}");
        assertThrows(ApiException.class, () -> extService.deriveParameterVersionFromRepair(pending, "test"));
        // 缺 measurementRef
        long noRef = fx.extRepo.insert("REPAIR_ACTION", "R-N", "维修系统", "v1", fx.segA,
                t(2026, 9, 1, 9, 0),
                "{\"acceptance\":{\"result\":\"ACCEPTED\",\"acceptedAt\":\"2026-09-01T09:00:00+08:00\"},\"measuredChanges\":{\"diameterMm\":[995,1000]}}");
        assertThrows(ApiException.class, () -> extService.deriveParameterVersionFromRepair(noRef, "test"));
        // 合规：有验收 + 可追溯
        long ok = fx.extRepo.insert("REPAIR_ACTION", "R-OK", "维修系统", "v1", fx.segA,
                t(2026, 9, 1, 9, 0),
                "{\"acceptance\":{\"result\":\"ACCEPTED\",\"acceptedAt\":\"2026-09-01T09:00:00+08:00\"},"
                        + "\"measuredChanges\":{\"diameterMm\":[995,1000]},\"measurementRef\":\"M-2026-09\"}");
        ParamVersion v2 = extService.deriveParameterVersionFromRepair(ok, "test");
        assertEquals(2, v2.versionNo());
        assertEquals(995.0, v2.diameterMinMm());
        assertEquals("REPAIR_ACCEPTANCE", v2.source());
        // 旧版本关闭但保留；糙率继承 v1
        var all = paramRepo.listBySegment(fx.segA);
        assertEquals(2, all.size());
        assertNotNull(all.get(0).validTo());
        assertEquals(0.012, v2.roughnessMin(), "未实测项继承上一版本");
    }
}
