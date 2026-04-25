package com.loganalysis.search.infrastructure.permission;

import com.loganalysis.common.util.JsonUtil;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯云 CLS 权限错误分析器，逐字对齐 app.py 中的 CLSPermissionAnalyzer 类。
 * 纯静态工具，不依赖 Spring。
 */
public final class ClsPermissionAnalyzer {

    public static final Map<String, String> CLS_ACTIONS;
    public static final Map<String, String> CONDITION_OPERATORS;
    public static final Map<String, String> REGIONS;

    static {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("cls:SearchLog", "搜索日志");
        a.put("cls:GetLog", "获取日志");
        a.put("cls:DescribeTopics", "查询日志主题");
        a.put("cls:DescribeLogsets", "查询日志集");
        a.put("cls:CreateTopic", "创建日志主题");
        a.put("cls:DeleteTopic", "删除日志主题");
        a.put("cls:ModifyTopic", "修改日志主题");
        a.put("cls:UploadLog", "上传日志");
        a.put("cls:DescribeIndex", "查询索引配置");
        a.put("cls:CreateIndex", "创建索引配置");
        a.put("cls:ModifyIndex", "修改索引配置");
        a.put("cls:DescribeAlarms", "查询告警策略");
        a.put("cls:CreateAlarm", "创建告警策略");
        a.put("cls:DescribeShippers", "查询投递任务");
        a.put("cls:CreateShipper", "创建投递任务");
        CLS_ACTIONS = Collections.unmodifiableMap(a);

        Map<String, String> c = new LinkedHashMap<>();
        c.put("ip_equal", "IP地址等于");
        c.put("ip_not_equal", "IP地址不等于");
        c.put("string_equal", "字符串等于");
        c.put("string_not_equal", "字符串不等于");
        c.put("null_equal", "值为空");
        c.put("null_not_equal", "值不为空");
        c.put("string_like", "字符串匹配");
        c.put("string_not_like", "字符串不匹配");
        CONDITION_OPERATORS = Collections.unmodifiableMap(c);

        Map<String, String> r = new LinkedHashMap<>();
        r.put("ap-guangzhou", "广州");
        r.put("ap-shanghai", "上海");
        r.put("ap-nanjing", "南京");
        r.put("ap-beijing", "北京");
        r.put("ap-chengdu", "成都");
        r.put("ap-chongqing", "重庆");
        r.put("ap-hongkong", "香港");
        r.put("ap-singapore", "新加坡");
        r.put("ap-tokyo", "东京");
        r.put("ap-seoul", "首尔");
        r.put("ap-bangkok", "曼谷");
        r.put("ap-mumbai", "孟买");
        r.put("eu-frankfurt", "法兰克福");
        r.put("na-siliconvalley", "硅谷");
        r.put("na-ashburn", "弗吉尼亚");
        REGIONS = Collections.unmodifiableMap(r);
    }

    private static final Pattern REQ_ID = Pattern.compile(
            "\\[request id:([^\\]]+)\\]|RequestId:\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern OP_RE = Pattern.compile("operation \\(([^)]+)\\)");
    private static final Pattern RESOURCE_RE = Pattern.compile("resource \\(([^)]+)\\)");
    private static final Pattern QCS_RE = Pattern.compile(
            "qcs::(\\w+):([^:]*):uin/(\\d+):(\\w+)/([^\\s]+)");

    private ClsPermissionAnalyzer() {}

    public static Map<String, Object> parseErrorMessage(String errorMessage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", null);
        result.put("operation", null);
        result.put("operation_desc", null);
        result.put("resource", null);
        result.put("resource_type", null);
        result.put("region", null);
        result.put("region_name", null);
        result.put("uin", null);
        result.put("topic_id", null);
        result.put("conditions", new ArrayList<Map<String, Object>>());
        result.put("strategy_ids", new ArrayList<Object>());
        result.put("raw_message", errorMessage);

        if (errorMessage == null) return result;

        Matcher m = REQ_ID.matcher(errorMessage);
        if (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            result.put("request_id", g1 != null ? g1 : g2);
        }

        Matcher om = OP_RE.matcher(errorMessage);
        if (om.find()) {
            String op = om.group(1);
            result.put("operation", op);
            result.put("operation_desc", CLS_ACTIONS.getOrDefault(op, "未知操作"));
        }

        Matcher rm = RESOURCE_RE.matcher(errorMessage);
        if (rm.find()) {
            String resource = rm.group(1);
            result.put("resource", resource);
            Matcher qm = QCS_RE.matcher(resource);
            if (qm.find()) {
                result.put("resource_type", qm.group(1));
                result.put("region", qm.group(2));
                result.put("region_name", REGIONS.getOrDefault(qm.group(2), qm.group(2)));
                result.put("uin", qm.group(3));
                String subtype = qm.group(4);
                result.put("topic_id", "topic".equals(subtype) ? qm.group(5) : null);
            }
        }

        int condStart = errorMessage.indexOf("condition:[");
        if (condStart != -1) {
            try {
                int startIdx = condStart + "condition:".length();
                int bracketCount = 0;
                int endIdx = startIdx;
                for (int i = 0; i < errorMessage.length() - startIdx; i++) {
                    char ch = errorMessage.charAt(startIdx + i);
                    if (ch == '[') bracketCount++;
                    else if (ch == ']') {
                        bracketCount--;
                        if (bracketCount == 0) {
                            endIdx = startIdx + i + 1;
                            break;
                        }
                    }
                }
                String condStr = errorMessage.substring(startIdx, endIdx);
                JsonNode arr = JsonUtil.mapper().readTree(condStr);
                if (arr.isArray()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> conditions = (List<Map<String, Object>>) result.get("conditions");
                    @SuppressWarnings("unchecked")
                    List<Object> strategyIds = (List<Object>) result.get("strategy_ids");
                    for (JsonNode group : arr) {
                        if (!group.isObject()) continue;
                        String effect = group.has("effect") ? group.get("effect").asText("unknown") : "unknown";
                        Object strategyId = null;
                        if (group.has("strategyId")) {
                            JsonNode sid = group.get("strategyId");
                            strategyId = sid.isIntegralNumber() ? (Object) sid.asLong() : sid.asText();
                            strategyIds.add(strategyId);
                        }
                        JsonNode conds = group.get("condition");
                        if (conds != null && conds.isArray()) {
                            for (JsonNode cond : conds) {
                                Map<String, Object> parsed = new LinkedHashMap<>();
                                parsed.put("key", cond.path("key").asText(""));
                                parsed.put("value", cond.path("value").asText(""));
                                String ope = cond.path("ope").asText("");
                                parsed.put("operator", ope);
                                parsed.put("operator_desc", CONDITION_OPERATORS.getOrDefault(ope, ope));
                                parsed.put("effect", effect);
                                parsed.put("strategy_id", strategyId);
                                conditions.add(parsed);
                            }
                        }
                    }
                }
            } catch (Exception ignore) { /* 与 Python JSONDecodeError/IndexError pass 一致 */ }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> generateFixSuggestions(Map<String, Object> parsed) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        String op = (String) parsed.get("operation");
        String opDesc = (String) parsed.get("operation_desc");
        Object topicId = parsed.get("topic_id");

        if (op != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "action_permission");
            s.put("title", "添加操作权限");
            s.put("description", "需要为当前账号或子账号授予 " + op + " (" + opDesc + ") 权限");
            s.put("priority", "high");
            suggestions.add(s);
        }
        if (topicId != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "resource_permission");
            s.put("title", "资源级别授权");
            s.put("description", "需要对Topic " + topicId + " 进行授权");
            s.put("priority", "high");
            suggestions.add(s);
        }

        List<Map<String, Object>> conditions = (List<Map<String, Object>>) parsed.get("conditions");
        for (Map<String, Object> cond : conditions) {
            String key = String.valueOf(cond.getOrDefault("key", ""));
            String operator = String.valueOf(cond.getOrDefault("operator", ""));
            Object sid = cond.get("strategy_id");
            if ("qcs:ip".equals(key) && "ip_not_equal".equals(operator)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", "ip_restriction");
                s.put("title", "IP访问限制");
                s.put("description", "当前策略限制了IP访问，您的IP可能不在允许列表中。策略ID: " + sid);
                s.put("priority", "high");
                s.put("fix_action", "联系管理员将您的IP添加到允许列表，或修改/删除IP限制策略");
                suggestions.add(s);
            } else if ("vpc:requester_vpc".equals(key) && "null_equal".equals(operator)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", "vpc_restriction");
                s.put("title", "VPC访问限制");
                s.put("description", "当前策略要求通过VPC内网访问，但您可能是从公网访问。策略ID: " + sid);
                s.put("priority", "high");
                s.put("fix_action", "通过VPC内网访问CLS服务，或修改策略允许公网访问");
                suggestions.add(s);
            }
        }

        List<Object> strategyIds = (List<Object>) parsed.get("strategy_ids");
        if (!strategyIds.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "policy_modification");
            s.put("title", "修改限制策略");
            s.put("description", "涉及的策略ID: " + join(strategyIds, ", "));
            s.put("priority", "medium");
            s.put("fix_action", "在腾讯云CAM控制台查看并修改相关策略");
            suggestions.add(s);
        }

        return suggestions;
    }

    public static Map<String, Object> generateIamPolicy(Map<String, Object> parsed) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", "2.0");
        List<Map<String, Object>> statements = new ArrayList<>();
        Map<String, Object> stmt = new LinkedHashMap<>();
        stmt.put("effect", "allow");
        String op = (String) parsed.get("operation");
        stmt.put("action", op != null ? Collections.singletonList(op) : Collections.singletonList("cls:*"));
        stmt.put("resource", Collections.singletonList("*"));

        Object resource = parsed.get("resource");
        Object topicId = parsed.get("topic_id");
        Object region = parsed.get("region");
        Object uin = parsed.get("uin");
        if (resource != null) {
            stmt.put("resource", Collections.singletonList(resource));
        } else if (topicId != null && region != null && uin != null) {
            stmt.put("resource", Collections.singletonList(
                    "qcs::cls:" + region + ":uin/" + uin + ":topic/" + topicId));
        }
        statements.add(stmt);
        policy.put("statement", statements);
        return policy;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> generateFixSteps(Map<String, Object> parsed, List<Map<String, Object>> ignore) {
        List<Map<String, Object>> steps = new ArrayList<>();

        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("step", 1);
        step1.put("title", "确认权限问题");
        step1.put("description", "操作 " + parsed.get("operation_desc") + " 被拒绝");
        step1.put("details", Arrays.asList(
                "Request ID: " + parsed.get("request_id"),
                "资源: " + parsed.get("resource"),
                "地域: " + parsed.get("region_name") + " (" + parsed.get("region") + ")"));
        steps.add(step1);

        List<Map<String, Object>> conditions = (List<Map<String, Object>>) parsed.get("conditions");
        boolean hasConds = conditions != null && !conditions.isEmpty();
        if (hasConds) {
            Map<String, Object> step2 = new LinkedHashMap<>();
            step2.put("step", 2);
            step2.put("title", "检查访问条件限制");
            step2.put("description", "以下条件可能阻止了您的访问");
            List<String> details = new ArrayList<>();
            for (Map<String, Object> c : conditions) {
                details.add("条件: " + c.get("key") + " " + c.get("operator_desc") + " (效果: " + c.get("effect") + ")");
            }
            step2.put("details", details);
            steps.add(step2);
        }

        Map<String, Object> step3 = new LinkedHashMap<>();
        step3.put("step", hasConds ? 3 : 2);
        step3.put("title", "登录腾讯云CAM控制台");
        step3.put("description", "访问 https://console.cloud.tencent.com/cam/policy 管理策略");
        step3.put("details", Arrays.asList(
                "使用主账号或具有CAM管理权限的子账号登录",
                "在策略管理页面搜索相关策略"));
        steps.add(step3);

        int nextStep = hasConds ? 4 : 3;
        List<Object> strategyIds = (List<Object>) parsed.get("strategy_ids");
        if (strategyIds != null && !strategyIds.isEmpty()) {
            Map<String, Object> step4 = new LinkedHashMap<>();
            step4.put("step", nextStep);
            step4.put("title", "修改限制策略");
            step4.put("description", "找到并修改策略ID: " + join(strategyIds, ", "));
            step4.put("details", Arrays.asList(
                    "如果是IP限制，添加您的IP到允许列表",
                    "如果是VPC限制，考虑添加公网访问条件或通过VPC访问",
                    "或者删除该限制策略"));
            steps.add(step4);
            nextStep++;
        }

        Map<String, Object> step5 = new LinkedHashMap<>();
        step5.put("step", nextStep);
        step5.put("title", "添加CLS操作权限");
        step5.put("description", "为用户/角色添加必要的CLS权限");
        step5.put("details", Arrays.asList(
                "添加 " + parsed.get("operation") + " 权限",
                "可以使用预设策略 QcloudCLSFullAccess 或 QcloudCLSReadOnlyAccess",
                "或创建自定义策略精确控制权限范围"));
        steps.add(step5);

        Map<String, Object> step6 = new LinkedHashMap<>();
        step6.put("step", nextStep + 1);
        step6.put("title", "验证修复效果");
        step6.put("description", "重新执行操作，确认权限问题已解决");
        step6.put("details", Arrays.asList(
                "等待策略生效（通常几秒到几分钟）",
                "重新执行之前失败的操作",
                "如仍有问题，检查是否有其他限制策略"));
        steps.add(step6);

        return steps;
    }

    private static String join(Collection<?> items, String sep) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object it : items) {
            if (!first) sb.append(sep);
            sb.append(it);
            first = false;
        }
        return sb.toString();
    }
}
