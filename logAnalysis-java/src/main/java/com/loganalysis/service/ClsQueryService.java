package com.loganalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loganalysis.util.JsonUtil;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.SearchLogRequest;
import com.tencentcloudapi.cls.v20201016.models.SearchLogResponse;
import com.tencentcloudapi.common.AbstractModel;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CLS 查询服务，对齐 app.py 中的 call_cls_api。
 *
 * - 凭证：由 CredentialService 按 credential_id 解密取得
 * - endpoint：默认 cls.internal.tencentcloudapi.com（内网），与 Python 原实现一致
 * - 返回结构：Map<String, Object> 形状 { "Response": {...}, "_debug": {...} }
 *   失败时 Response 下包含 Error { Code, Message }，不抛异常，便于 Controller 直接透传
 *   （对齐原 Python 行为）
 */
@Service
public class ClsQueryService {

    private static final Logger log = LoggerFactory.getLogger(ClsQueryService.class);

    @Autowired
    private CredentialService credentialService;

    @Autowired(required = false)
    private TopicLookup topicLookup; // 见下方内嵌 @Service，允许为空仅为单测时便利

    @Value("${tencent.cloud.cls.endpoint:cls.internal.tencentcloudapi.com}")
    private String defaultEndpoint;

    @Value("${tencent.cloud.cls.default-region:ap-guangzhou}")
    private String defaultRegion;

    /**
     * 调用 CLS SearchLog
     *
     * @param credentialId 凭证 id（api_credentials.id）
     * @param topicId      CLS TopicId
     * @param query        查询语句
     * @param fromTime     起始时间戳（毫秒）
     * @param toTime       结束时间戳（毫秒）
     * @param limit        返回数量
     * @param sort         desc/asc
     * @param syntaxRule   语法规则（0=Lucene, 1=CQL）
     * @param region       可选，null 时尝试从 log_topics 查，否则走默认
     * @return 结构见类文档
     */
    public Map<String, Object> searchLog(long credentialId, String topicId, String query,
                                         long fromTime, long toTime, int limit, String sort,
                                         int syntaxRule, String region) {
        Map<String, Object> cred = credentialService.loadDecrypted(credentialId);
        if (cred == null) {
            return errorResult("InvalidParameter", "凭证不存在", region, topicId, fromTime, toTime, query);
        }
        String sid = String.valueOf(cred.get("secret_id_plain"));
        String skey = String.valueOf(cred.get("secret_key_plain"));

        String effectiveRegion = region;
        if (isEmpty(effectiveRegion) && topicLookup != null) {
            effectiveRegion = topicLookup.regionOfTopic(topicId);
        }
        if (isEmpty(effectiveRegion)) {
            effectiveRegion = defaultRegion;
        }

        try {
            Credential credObj = new Credential(sid, skey);
            HttpProfile hp = new HttpProfile();
            hp.setEndpoint(defaultEndpoint);
            hp.setReqMethod("POST");
            ClientProfile cp = new ClientProfile();
            cp.setHttpProfile(hp);
            ClsClient client = new ClsClient(credObj, effectiveRegion, cp);

            SearchLogRequest req = new SearchLogRequest();
            req.setTopicId(topicId);
            req.setFrom(fromTime);
            req.setTo(toTime);
            req.setQuery(query);
            req.setLimit((long) limit);
            req.setSort(isEmpty(sort) ? "desc" : sort);
            req.setSyntaxRule((long) syntaxRule);
            req.setUseNewAnalysis(true);

            SearchLogResponse resp = client.SearchLog(req);

            // 将 SDK 的 JSON 字符串转为通用 Map，再包装成 {"Response": ...}
            JsonNode node = JsonUtil.mapper().readTree(AbstractModel.toJsonString(resp));
            Map<String, Object> response = JsonUtil.mapper().convertValue(node, Map.class);

            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("Response", response);
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("region", effectiveRegion);
            debug.put("topic_id", topicId);
            debug.put("from_time", fromTime);
            debug.put("to_time", toTime);
            debug.put("query", query);
            ret.put("_debug", debug);
            return ret;
        } catch (TencentCloudSDKException e) {
            log.warn("CLS SDK 异常: code={}, message={}, requestId={}", e.getErrorCode(), e.getMessage(), e.getRequestId());
            return errorResult(e.getErrorCode(), e.getMessage(), effectiveRegion, topicId, fromTime, toTime, query,
                    e.getRequestId());
        } catch (Exception e) {
            log.warn("CLS 调用内部异常: {}", e.getMessage());
            return errorResult("InternalError", e.getMessage(), effectiveRegion, topicId, fromTime, toTime, query);
        }
    }

    private static Map<String, Object> errorResult(String code, String msg, String region, String topicId,
                                                   Long from, Long to, String query) {
        return errorResult(code, msg, region, topicId, from, to, query, null);
    }

    private static Map<String, Object> errorResult(String code, String msg, String region, String topicId,
                                                   Long from, Long to, String query, String reqId) {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("Code", code == null ? "InternalError" : code);
        err.put("Message", msg);
        response.put("Error", err);
        if (reqId != null) response.put("RequestId", reqId);

        Map<String, Object> debug = new LinkedHashMap<>();
        if (region != null) debug.put("region", region);
        if (topicId != null) debug.put("topic_id", topicId);
        if (from != null) debug.put("from_time", from);
        if (to != null) debug.put("to_time", to);
        if (query != null) debug.put("query", query);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("Response", response);
        ret.put("_debug", debug);
        return ret;
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    /**
     * 内置 topic → region 查询组件。
     * 单独抽出，便于后续被其它地方复用/替换，也让 ClsQueryService 不直接碰 JdbcTemplate。
     */
    @Service
    public static class TopicLookup {
        @Autowired
        private org.springframework.jdbc.core.JdbcTemplate jdbc;

        public String regionOfTopic(String topicId) {
            if (topicId == null || topicId.isEmpty()) return null;
            try {
                List<String> rs = jdbc.query(
                        "SELECT region FROM log_topics WHERE topic_id = ? LIMIT 1",
                        (rs0, i) -> rs0.getString(1), topicId);
                return rs.isEmpty() ? null : rs.get(0);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
