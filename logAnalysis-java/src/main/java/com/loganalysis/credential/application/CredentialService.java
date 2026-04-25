package com.loganalysis.credential.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.loganalysis.common.util.CryptoUtil;
import com.loganalysis.credential.infrastructure.persistence.mapper.CredentialMapper;
import com.loganalysis.credential.infrastructure.persistence.po.CredentialPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * api_credentials 表的 CRUD 服务。
 *
 * 对齐原 app.py 中的：
 *   - GET    /api/credentials
 *   - GET    /api/credentials/&lt;id&gt;
 *   - POST   /api/credentials
 *   - PUT    /api/credentials/&lt;id&gt;
 *   - DELETE /api/credentials/&lt;id&gt;
 *
 * 数据库列：id, name, secret_id, secret_key, region, created_at, updated_at
 * secret_id / secret_key 在 DB 中以 Fernet 加密存储。
 *
 * P2b-3 起：底层走 MyBatis-Plus {@link CredentialMapper}；对外方法签名和返回 Map 的
 * 字段顺序与迁移前严格一致（diff 工具对比字段顺序敏感）。
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private CryptoUtil crypto;

    /** 列出全部（脱敏） */
    public List<Map<String, Object>> listMasked() {
        List<CredentialPO> pos = credentialMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>(pos.size());
        for (CredentialPO po : pos) {
            Map<String, Object> out = new LinkedHashMap<>();
            // 字段顺序对齐原代码：id, name, created_at, updated_at, secret_id_masked, secret_key_masked
            out.put("id", po.getId());
            out.put("name", po.getName());
            out.put("created_at", formatTs(po.getCreatedAt()));
            out.put("updated_at", formatTs(po.getUpdatedAt()));
            try {
                String id = crypto.decrypt(po.getSecretId());
                String key = crypto.decrypt(po.getSecretKey());
                out.put("secret_id_masked", CryptoUtil.mask(id, 4));
                out.put("secret_key_masked", CryptoUtil.mask(key, 3));
            } catch (Exception e) {
                out.put("secret_id_masked", "********");
                out.put("secret_key_masked", "********");
            }
            result.add(out);
        }
        return result;
    }

    /** 详情（含解密后 secret_id 的明文，用于回显编辑） */
    public Map<String, Object> detail(long id) {
        CredentialPO po = credentialMapper.selectById(id);
        if (po == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", po.getId());
        r.put("name", po.getName());
        r.put("created_at", formatTs(po.getCreatedAt()));
        r.put("updated_at", formatTs(po.getUpdatedAt()));
        try {
            String sid = crypto.decrypt(po.getSecretId());
            String skey = crypto.decrypt(po.getSecretKey());
            r.put("secret_id_masked", CryptoUtil.mask(sid, 4));
            r.put("secret_key_masked", CryptoUtil.mask(skey, 3));
            r.put("secret_id_full", sid);
        } catch (Exception e) {
            r.put("secret_id_masked", "********");
            r.put("secret_key_masked", "********");
            r.put("secret_id_full", "");
        }
        return r;
    }

    /** 新建。返回生成的主键 id，失败时抛 IllegalArgumentException（名称冲突）或运行时异常 */
    @Transactional
    public long create(String name, String secretId, String secretKey) {
        if (name == null || name.isEmpty() || secretId == null || secretKey == null) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        try {
            CredentialPO po = new CredentialPO();
            po.setName(name);
            po.setSecretId(crypto.encrypt(secretId));
            po.setSecretKey(crypto.encrypt(secretKey));
            credentialMapper.insert(po);
            return po.getId() == null ? 0L : po.getId();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("名称已存在");
        }
    }

    /** 更新：仅更新非空字段（对齐原行为） */
    @Transactional
    public void update(long id, String name, String secretId, String secretKey) {
        LambdaUpdateWrapper<CredentialPO> uw = new LambdaUpdateWrapper<>();
        boolean any = false;
        if (name != null && !name.isEmpty()) {
            uw.set(CredentialPO::getName, name);
            any = true;
        }
        if (secretId != null && !secretId.isEmpty()) {
            uw.set(CredentialPO::getSecretId, crypto.encrypt(secretId));
            any = true;
        }
        if (secretKey != null && !secretKey.isEmpty()) {
            uw.set(CredentialPO::getSecretKey, crypto.encrypt(secretKey));
            any = true;
        }
        if (!any) return;
        uw.eq(CredentialPO::getId, id);
        credentialMapper.update(null, uw);
    }

    public int delete(long id) {
        return credentialMapper.deleteById(id);
    }

    /**
     * 读取解密后的完整凭证（secret_id / secret_key 明文），用于 CLS 调用。
     * 返回 Map 含 id/name/region/secret_id_plain/secret_key_plain。
     */
    public Map<String, Object> loadDecrypted(long id) {
        CredentialPO po = credentialMapper.selectById(id);
        if (po == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", po.getId());
        r.put("name", po.getName());
        r.put("region", po.getRegion());
        r.put("created_at", formatTs(po.getCreatedAt()));
        r.put("updated_at", formatTs(po.getUpdatedAt()));
        r.put("secret_id_plain", crypto.decrypt(po.getSecretId()));
        r.put("secret_key_plain", crypto.decrypt(po.getSecretKey()));
        return r;
    }

    private static String formatTs(LocalDateTime dt) {
        return dt == null ? null : dt.toString();
    }
}
