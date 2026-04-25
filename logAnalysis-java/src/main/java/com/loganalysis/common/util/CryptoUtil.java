package com.loganalysis.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Fernet 兼容加解密工具
 *
 * 对齐原 Python 端 cryptography.Fernet：
 *   Token = 0x80 || timestamp(8B,BE) || iv(16B) || ciphertext(AES-128-CBC,PKCS7) || hmac-sha256(32B)
 *   再整体 URL-safe Base64 编码。
 *   32 字节密钥：前 16B 作为 HMAC key，后 16B 作为 AES key。
 *
 * 密钥文件路径：loganalysis.encryption.key-file （默认 .encryption_key）
 * 若密钥文件不存在则自动生成，与原 get_encryption_key() 行为一致。
 */
@Component
public class CryptoUtil {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtil.class);

    private static final byte VERSION = (byte) 0x80;

    @Value("${loganalysis.encryption.key-file:.encryption_key}")
    private String keyFilePath;

    /**
     * 生产环境应设为 true，禁止自动生成密钥。
     * 这样当密钥文件不存在时会直接失败，避免静默生成新密钥导致历史加密数据全部解不开。
     */
    @Value("${loganalysis.encryption.forbid-auto-generate:false}")
    private boolean forbidAutoGenerate;

    private byte[] signingKey;    // 16 bytes
    private byte[] encryptionKey; // 16 bytes

    @PostConstruct
    public void init() {
        try {
            byte[] raw = loadOrGenerateKey();
            if (raw.length != 32) {
                throw new IllegalStateException("Fernet key must be 32 bytes url-safe base64 decoded, got: " + raw.length);
            }
            this.signingKey = Arrays.copyOfRange(raw, 0, 16);
            this.encryptionKey = Arrays.copyOfRange(raw, 16, 32);
            log.info("CryptoUtil 初始化完成，密钥文件: {}", keyFilePath);
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    private byte[] loadOrGenerateKey() throws IOException {
        Path p = Paths.get(keyFilePath);
        if (Files.exists(p)) {
            // Fernet 密钥文件原样：URL-safe base64 字符串（44 字节含 padding=）
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            return Base64.getUrlDecoder().decode(content);
        }
        // 生产环境禁止自动生成：避免静默产生新密钥导致历史数据全部解不开
        if (forbidAutoGenerate) {
            throw new IllegalStateException(
                    "密钥文件不存在且已禁用自动生成: " + p.toAbsolutePath() +
                    "。请将 Python 版的 .encryption_key 拷贝到此路径，或设置 " +
                    "loganalysis.encryption.key-file 指向正确位置。");
        }
        // 生成新密钥（与 Fernet.generate_key() 等价）
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String encoded = Base64.getUrlEncoder().encodeToString(raw);
        Files.write(p, encoded.getBytes(StandardCharsets.UTF_8));
        log.warn("未找到密钥文件，已生成新密钥到: {}", p.toAbsolutePath());
        return raw;
    }

    /** 加密明文，返回 URL-safe Base64 token */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            long ts = System.currentTimeMillis() / 1000L;
            byte[] body = new byte[1 + 8 + iv.length + ct.length];
            body[0] = VERSION;
            for (int i = 0; i < 8; i++) {
                body[1 + i] = (byte) ((ts >> ((7 - i) * 8)) & 0xff);
            }
            System.arraycopy(iv, 0, body, 9, 16);
            System.arraycopy(ct, 0, body, 25, ct.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] hmac = mac.doFinal(body);

            byte[] token = new byte[body.length + hmac.length];
            System.arraycopy(body, 0, token, 0, body.length);
            System.arraycopy(hmac, 0, token, body.length, hmac.length);
            return Base64.getUrlEncoder().encodeToString(token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密 Fernet token */
    public String decrypt(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(token);
            if (data.length < 1 + 8 + 16 + 32 || data[0] != VERSION) {
                throw new GeneralSecurityException("invalid Fernet token");
            }
            int macStart = data.length - 32;
            byte[] signed = Arrays.copyOfRange(data, 0, macStart);
            byte[] providedMac = Arrays.copyOfRange(data, macStart, data.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] expectedMac = mac.doFinal(signed);
            if (!MessageDigest.isEqual(providedMac, expectedMac)) {
                throw new GeneralSecurityException("hmac mismatch");
            }

            byte[] iv = Arrays.copyOfRange(data, 9, 25);
            byte[] ciphertext = Arrays.copyOfRange(data, 25, macStart);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解密失败: " + e.getMessage(), e);
        }
    }

    /** 脱敏显示密钥，对齐原 mask_secret */
    public static String mask(String value, int showChars) {
        if (value == null || value.length() <= showChars * 2) {
            return "********";
        }
        return value.substring(0, showChars) + "********" + value.substring(value.length() - showChars);
    }

    public static String mask(String value) {
        return mask(value, 4);
    }

    // 仅用于测试/工具：生成一个新的 Fernet 密钥（URL-safe base64，32B 原始数据）
    public static String generateKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        byte[] raw = kg.generateKey().getEncoded();
        return Base64.getUrlEncoder().encodeToString(raw);
    }
}
