package com.loganalysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * GwHitchProcessor 的纯静态辅助方法测试。
 * 依赖 DB/Redis 的 processLogs 由 Controller 层 MockMvc 测试覆盖。
 */
class GwHitchProcessorTest {

    /* ========== extractInterfaceName ========== */

    @Test
    void extractInterfaceName_stripsHttpPrefix() {
        assertThat(GwHitchProcessor.extractInterfaceName("POST /hitchride/order/addition"))
                .isEqualTo("/hitchride/order/addition");
        assertThat(GwHitchProcessor.extractInterfaceName("GET /api/user/info"))
                .isEqualTo("/api/user/info");
    }

    @Test
    void extractInterfaceName_edgeCases() {
        assertThat(GwHitchProcessor.extractInterfaceName("")).isEqualTo("");
        assertThat(GwHitchProcessor.extractInterfaceName(null)).isEqualTo("");
        // 无 /：返回原值
        assertThat(GwHitchProcessor.extractInterfaceName("HELLO")).isEqualTo("HELLO");
        // 单 /：从第一个开始
        assertThat(GwHitchProcessor.extractInterfaceName("/api/x")).isEqualTo("/api/x");
    }

    /* ========== parseResponseBody ========== */

    @Test
    void parseResponseBody_prefersResData() {
        GwHitchProcessor.ParsedResp r = GwHitchProcessor.parseResponseBody(
                "{\"errCode\":0,\"errMsg\":\"success\",\"resData\":{\"code\":37,\"message\":\"出发时间太近\"}}");
        assertThat(r.code).isInstanceOf(Number.class);
        assertThat(((Number) r.code).intValue()).isEqualTo(37);
        assertThat(r.message).isEqualTo("出发时间太近");
    }

    @Test
    void parseResponseBody_fallbackToErrCode() {
        GwHitchProcessor.ParsedResp r = GwHitchProcessor.parseResponseBody(
                "{\"errCode\":500,\"errMsg\":\"系统错误\"}");
        assertThat(((Number) r.code).intValue()).isEqualTo(500);
        assertThat(r.message).isEqualTo("系统错误");
    }

    @Test
    void parseResponseBody_resDataPresentButNoCode_fallsBack() {
        // resData 存在但 code 和 message 均为 null，应回退到外层
        GwHitchProcessor.ParsedResp r = GwHitchProcessor.parseResponseBody(
                "{\"errCode\":400,\"errMsg\":\"bad\",\"resData\":{\"other\":\"x\"}}");
        assertThat(((Number) r.code).intValue()).isEqualTo(400);
        assertThat(r.message).isEqualTo("bad");
    }

    @Test
    void parseResponseBody_emptyOrInvalid() {
        GwHitchProcessor.ParsedResp r = GwHitchProcessor.parseResponseBody("");
        assertThat(r.code).isNull();
        assertThat(r.message).isNull();

        GwHitchProcessor.ParsedResp r2 = GwHitchProcessor.parseResponseBody("not-json");
        assertThat(r2.code).isNull();
    }
}
