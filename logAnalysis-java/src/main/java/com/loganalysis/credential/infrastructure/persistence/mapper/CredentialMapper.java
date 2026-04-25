package com.loganalysis.credential.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.credential.infrastructure.persistence.po.CredentialPO;

/**
 * api_credentials Mapper。
 *
 * BaseMapper 提供的方法已覆盖本 Service 需要的 CRUD：
 *   - {@code insert(PO)}：新增，自动回填主键
 *   - {@code selectById(id)}：按 id 查
 *   - {@code selectList(null)}：查全部
 *   - {@code updateById(PO)} / {@code update(PO, wrapper)}：更新
 *   - {@code deleteById(id)}：删除
 *
 * 注意：Service 层的"仅更新非空字段"语义用 {@code UpdateWrapper} 在 Service 内表达。
 */
public interface CredentialMapper extends BaseMapper<CredentialPO> {
}
