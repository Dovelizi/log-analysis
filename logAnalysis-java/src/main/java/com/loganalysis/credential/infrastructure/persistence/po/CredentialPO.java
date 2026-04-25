package com.loganalysis.credential.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 腾讯云 CLS 凭证表 PO（api_credentials）。
 *
 * secret_id / secret_key 为 Fernet 加密后的密文，业务层使用前必须通过
 * {@link com.loganalysis.common.util.CryptoUtil#decrypt(String)} 解密。
 */
@Data
@TableName("api_credentials")
public class CredentialPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** Fernet 加密后的 secret_id */
    private String secretId;

    /** Fernet 加密后的 secret_key */
    private String secretKey;

    private String region;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
