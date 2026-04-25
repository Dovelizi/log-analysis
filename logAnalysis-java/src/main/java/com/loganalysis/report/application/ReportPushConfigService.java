package com.loganalysis.report.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loganalysis.common.util.JsonUtil;
import com.loganalysis.report.infrastructure.persistence.mapper.ReportPushConfigMapper;
import com.loganalysis.report.infrastructure.persistence.po.ReportPushConfigPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * report_push_config 表 CRUD，对齐 routes/report_routes.py push-configs 四个接口。
 *
 * push_mode 枚举: daily / date / relative
 *
 * P2b-3 起：底层走 MyBatis-Plus {@link ReportPushConfigMapper}；对外返回 Map 的
 * 字段顺序保持与迁移前一致（含 email_config 自动 JSON 解析、时间戳归一化）。
 */
@Service
public class ReportPushConfigService {

    private static final Set<String> ALLOWED_MODES = Set.of("daily", "date", "relative");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private ReportPushConfigMapper reportPushConfigMapper;

    public List<Map<String, Object>> listAll() {
        LambdaQueryWrapper<ReportPushConfigPO> w = new LambdaQueryWrapper<>();
        w.orderByDesc(ReportPushConfigPO::getCreateTime);
        List<ReportPushConfigPO> pos = reportPushConfigMapper.selectList(w);
        List<Map<String, Object>> list = new java.util.ArrayList<>(pos.size());
        for (ReportPushConfigPO po : pos) {
            list.add(toMap(po));
        }
        return list;
    }

    public long create(Map<String, Object> data) {
        validate(data, true);
        ReportPushConfigPO po = new ReportPushConfigPO();
        po.setName(str(data.get("name")));
        po.setPushType(strDefault(data.get("push_type"), "wecom"));
        po.setWebhookUrl(str(data.get("webhook_url")));
        po.setEmailConfig(jsonCell(data.get("email_config")));
        po.setScheduleEnabled(scheduleEnabledValue(data.get("schedule_enabled")));
        po.setScheduleCron(str(data.get("schedule_cron")));
        po.setScheduleTime(str(data.get("schedule_time")));
        po.setPushMode(strDefault(data.get("push_mode"), "daily"));
        po.setPushDate(parseDateOrNull(data.get("push_date")));
        po.setRelativeDays(toInt(data.get("relative_days"), 0));
        reportPushConfigMapper.insert(po);
        return po.getId() == null ? 0L : po.getId();
    }

    public void update(long id, Map<String, Object> data) {
        validate(data, false);
        ReportPushConfigPO po = new ReportPushConfigPO();
        po.setId(id);
        po.setName(str(data.get("name")));
        po.setPushType(strDefault(data.get("push_type"), "wecom"));
        po.setWebhookUrl(str(data.get("webhook_url")));
        po.setEmailConfig(jsonCell(data.get("email_config")));
        po.setScheduleEnabled(scheduleEnabledValue(data.get("schedule_enabled")));
        po.setScheduleCron(str(data.get("schedule_cron")));
        po.setScheduleTime(str(data.get("schedule_time")));
        po.setPushMode(strDefault(data.get("push_mode"), "daily"));
        po.setPushDate(parseDateOrNull(data.get("push_date")));
        po.setRelativeDays(toInt(data.get("relative_days"), 0));
        reportPushConfigMapper.updateById(po);
    }

    public int delete(long id) {
        return reportPushConfigMapper.deleteById(id);
    }

    public Map<String, Object> findById(long id) {
        ReportPushConfigPO po = reportPushConfigMapper.selectById(id);
        return po == null ? null : toMap(po);
    }

    // ============================== 内部 ==============================

    /**
     * 把 PO 转成原代码返回的 Map 结构，保留所有对外字段顺序。
     * 对齐原 normalizeRow：时间戳转 ISO 字符串、email_config JSON 解析。
     */
    private static Map<String, Object> toMap(ReportPushConfigPO po) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", po.getId());
        r.put("name", po.getName());
        r.put("push_type", po.getPushType());
        r.put("webhook_url", po.getWebhookUrl());
        // email_config：DB 存 JSON 字符串，对外返回 Map
        Object emailConfig = po.getEmailConfig();
        if (emailConfig instanceof String && !((String) emailConfig).isEmpty()) {
            Map<String, Object> parsed = JsonUtil.toMap((String) emailConfig);
            r.put("email_config", parsed != null ? parsed : emailConfig);
        } else {
            r.put("email_config", emailConfig);
        }
        r.put("schedule_enabled", po.getScheduleEnabled());
        r.put("schedule_cron", po.getScheduleCron());
        r.put("schedule_time", po.getScheduleTime());
        r.put("push_mode", po.getPushMode());
        r.put("push_date", po.getPushDate() == null ? null : po.getPushDate().toString());
        r.put("relative_days", po.getRelativeDays());
        r.put("last_push_time", formatTs(po.getLastPushTime()));
        r.put("last_scheduled_push_time", formatTs(po.getLastScheduledPushTime()));
        r.put("create_time", formatTs(po.getCreateTime()));
        r.put("update_time", formatTs(po.getUpdateTime()));
        return r;
    }

    private void validate(Map<String, Object> data, boolean forCreate) {
        if (forCreate) {
            if (isEmpty(str(data.get("name")))) {
                throw new IllegalArgumentException("缺少必要参数: name");
            }
            if (isEmpty(str(data.get("push_type")))) {
                throw new IllegalArgumentException("缺少必要参数: push_type");
            }
        }
        String pushMode = strDefault(data.get("push_mode"), "daily");
        if (!ALLOWED_MODES.contains(pushMode)) {
            throw new IllegalArgumentException("push_mode 必须是 daily/date/relative");
        }
        if ("date".equals(pushMode)) {
            if (parseDateOrNull(data.get("push_date")) == null) {
                throw new IllegalArgumentException("push_mode=date 时必须提供 push_date");
            }
        }
        if ("relative".equals(pushMode)) {
            int rd = toInt(data.get("relative_days"), -1);
            if (rd < 0) throw new IllegalArgumentException("push_mode=relative 时 relative_days 必须 >= 0");
        }
    }

    /** schedule_enabled 对齐原行为：Boolean.TRUE → 1；其他按数字处理 */
    private static int scheduleEnabledValue(Object v) {
        if (Boolean.TRUE.equals(v)) return 1;
        return toInt(v, 0);
    }

    /** email_config 接收 Map/List（前端 JSON）或字符串，写入时统一成字符串 */
    private static String jsonCell(Object v) {
        if (v == null) return null;
        if (v instanceof String) return ((String) v).isEmpty() ? null : (String) v;
        return JsonUtil.toJson(v);
    }

    /** push_date 可能是 LocalDate / java.sql.Date / "yyyy-MM-dd" 字符串；空串返回 null */
    private static LocalDate parseDateOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate) return (LocalDate) v;
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatTs(LocalDateTime dt) {
        return dt == null ? null : dt.toString();
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String strDefault(Object v, String def) {
        String s = str(v);
        return s == null || s.isEmpty() ? def : s;
    }
    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
