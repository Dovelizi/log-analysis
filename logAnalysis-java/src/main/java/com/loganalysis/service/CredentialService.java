package com.loganalysis.service;

import com.loganalysis.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

/**
 * api_credentials 表的 CRUD 服务。
 *
 * 对齐原 app.py 中的：
 *   - GET    /api/credentials
 *   - GET    /api/credentials/<id>
 *   - POST   /api/credentials
 *   - PUT    /api/credentials/<id>
 *   - DELETE /api/credentials/<id>
 *
 * 数据库列：id, name, secret_id, secret_key, region, created_at, updated_at
 * secret_id / secret_key 在 DB 中以 Fernet 加密存储。
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CryptoUtil crypto;

    /** 列出全部（脱敏） */
    public List<Map<String, Object>> listMasked() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, secret_id, secret_key, created_at, updated_at FROM api_credentials");
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> out = new LinkedHashMap<>(r);
            try {
                String id = crypto.decrypt(String.valueOf(r.get("secret_id")));
                String key = crypto.decrypt(String.valueOf(r.get("secret_key")));
                out.put("secret_id_masked", CryptoUtil.mask(id, 4));
                out.put("secret_key_masked", CryptoUtil.mask(key, 3));
            } catch (Exception e) {
                out.put("secret_id_masked", "********");
                out.put("secret_key_masked", "********");
            }
            out.remove("secret_id");
            out.remove("secret_key");
            normalizeTimestamps(out);
            result.add(out);
        }
        return result;
    }

    /** 详情（含解密后 secret_id 的明文，用于回显编辑） */
    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, secret_id, secret_key, created_at, updated_at FROM api_credentials WHERE id = ?",
                id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        try {
            String sid = crypto.decrypt(String.valueOf(r.get("secret_id")));
            String skey = crypto.decrypt(String.valueOf(r.get("secret_key")));
            r.put("secret_id_masked", CryptoUtil.mask(sid, 4));
            r.put("secret_key_masked", CryptoUtil.mask(skey, 3));
            r.put("secret_id_full", sid);
        } catch (Exception e) {
            r.put("secret_id_masked", "********");
            r.put("secret_key_masked", "********");
            r.put("secret_id_full", "");
        }
        r.remove("secret_id");
        r.remove("secret_key");
        normalizeTimestamps(r);
        return r;
    }

    /** 新建。返回生成的主键 id，失败时抛 IllegalArgumentException（名称冲突）或运行时异常 */
    @Transactional
    public long create(String name, String secretId, String secretKey) {
        if (name == null || name.isEmpty() || secretId == null || secretKey == null) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        String encId = crypto.encrypt(secretId);
        String encKey = crypto.encrypt(secretKey);
        KeyHolder kh = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO api_credentials (name, secret_id, secret_key) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, encId);
                ps.setString(3, encKey);
                return ps;
            }, kh);
            Number key = kh.getKey();
            return key == null ? 0L : key.longValue();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("名称已存在");
        }
    }

    /** 更新：仅更新非空字段 */
    @Transactional
    public void update(long id, String name, String secretId, String secretKey) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isEmpty()) { sets.add("name = ?"); args.add(name); }
        if (secretId != null && !secretId.isEmpty()) { sets.add("secret_id = ?"); args.add(crypto.encrypt(secretId)); }
        if (secretKey != null && !secretKey.isEmpty()) { sets.add("secret_key = ?"); args.add(crypto.encrypt(secretKey)); }
        if (sets.isEmpty()) return;
        args.add(id);
        String sql = "UPDATE api_credentials SET " + String.join(", ", sets) + " WHERE id = ?";
        jdbcTemplate.update(sql, args.toArray());
    }

    public int delete(long id) {
        return jdbcTemplate.update("DELETE FROM api_credentials WHERE id = ?", id);
    }

    /**
     * 读取解密后的完整凭证（secret_id / secret_key 明文），用于 CLS 调用。
     * 返回 Map 含 id/name/region/secret_id_plain/secret_key_plain。
     */
    public Map<String, Object> loadDecrypted(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM api_credentials WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        r.put("secret_id_plain", crypto.decrypt(String.valueOf(r.get("secret_id"))));
        r.put("secret_key_plain", crypto.decrypt(String.valueOf(r.get("secret_key"))));
        r.remove("secret_id");
        r.remove("secret_key");
        normalizeTimestamps(r);
        return r;
    }

    private static void normalizeTimestamps(Map<String, Object> m) {
        for (String k : new String[]{"created_at", "updated_at"}) {
            Object v = m.get(k);
            if (v instanceof LocalDateTime) {
                m.put(k, v.toString());
            } else if (v instanceof java.sql.Timestamp) {
                m.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
            }
        }
    }
}
