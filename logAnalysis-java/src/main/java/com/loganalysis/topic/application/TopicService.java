package com.loganalysis.topic.application;

import com.loganalysis.topic.infrastructure.persistence.mapper.TopicMapper;
import com.loganalysis.topic.infrastructure.persistence.po.TopicPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * log_topics 表 CRUD。
 *
 * 对齐原 app.py：
 *   GET    /api/topics              -&gt; listAll()
 *   POST   /api/topics               -&gt; create()
 *   PUT    /api/topics/&lt;id&gt;          -&gt; update()
 *   DELETE /api/topics/&lt;id&gt;          -&gt; delete()
 *
 * P2b-3 起：底层走 MyBatis-Plus {@link TopicMapper}。
 */
@Service
public class TopicService {

    @Autowired
    private TopicMapper topicMapper;

    /** 全量列表，带 credential_name（JOIN api_credentials），保持原返回结构 */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = topicMapper.listAllWithCredentialName();
        for (Map<String, Object> r : rows) normalizeTimestamps(r);
        return rows;
    }

    public long create(Map<String, Object> data) {
        Long credentialId = toLong(data.get("credential_id"));
        String topicId = str(data.get("topic_id"));
        if (credentialId == null || topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        TopicPO po = new TopicPO();
        po.setCredentialId(credentialId);
        po.setRegion(strDefault(data.get("region"), "ap-guangzhou"));
        po.setTopicId(topicId);
        po.setTopicName(strDefault(data.get("topic_name"), ""));
        po.setDescription(strDefault(data.get("description"), ""));
        topicMapper.insert(po);
        // 对齐原接口语义：返回固定 1（Python 版也是固定值，具体 id 由 controller 另行处理）
        return 1L;
    }

    public void update(long id, Map<String, Object> data) {
        TopicPO po = new TopicPO();
        po.setId(id);
        po.setRegion(strDefault(data.get("region"), "ap-guangzhou"));
        po.setTopicName(strDefault(data.get("topic_name"), ""));
        po.setDescription(strDefault(data.get("description"), ""));
        topicMapper.updateById(po);
    }

    public int delete(long id) {
        return topicMapper.deleteById(id);
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String strDefault(Object v, String def) {
        String s = str(v);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static void normalizeTimestamps(Map<String, Object> m) {
        Object v = m.get("created_at");
        if (v instanceof LocalDateTime) m.put("created_at", v.toString());
        else if (v instanceof java.sql.Timestamp) m.put("created_at", ((java.sql.Timestamp) v).toLocalDateTime().toString());
    }
}
