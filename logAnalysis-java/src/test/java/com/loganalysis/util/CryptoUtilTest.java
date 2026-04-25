package com.loganalysis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * CryptoUtil 单元测试：纯 JUnit 5，不启动 Spring。
 * 通过反射设置 keyFilePath 字段，手动调用 init()。
 */
class CryptoUtilTest {

    private CryptoUtil newUtil(Path keyPath) throws Exception {
        // 先生成一个稳定的 32B URL-safe base64 key 写入文件
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        Files.write(keyPath, Base64.getUrlEncoder().encodeToString(raw).getBytes(StandardCharsets.UTF_8));

        CryptoUtil util = new CryptoUtil();
        Field f = CryptoUtil.class.getDeclaredField("keyFilePath");
        f.setAccessible(true);
        f.set(util, keyPath.toString());
        util.init();
        return util;
    }

    @Test
    void encryptDecryptRoundTrip(@TempDir Path tmp) throws Exception {
        CryptoUtil util = newUtil(tmp.resolve(".encryption_key"));
        String plain = "hello world 中文密钥";
        String token = util.encrypt(plain);
        assertThat(token).isNotEmpty();
        String decrypted = util.decrypt(token);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void encryptEmptyReturnsEmpty(@TempDir Path tmp) throws Exception {
        CryptoUtil util = newUtil(tmp.resolve(".encryption_key"));
        assertThat(util.encrypt("")).isEqualTo("");
        assertThat(util.encrypt(null)).isEqualTo("");
        assertThat(util.decrypt("")).isEqualTo("");
        assertThat(util.decrypt(null)).isEqualTo("");
    }

    @Test
    void maskSecret() {
        // length > showChars*2：前 4 + 8*"*" + 后 4
        assertThat(CryptoUtil.mask("abcd1234wxyz", 4)).isEqualTo("abcd********wxyz");
        // length <= showChars*2：返回 ********
        assertThat(CryptoUtil.mask("12", 4)).isEqualTo("********");
        assertThat(CryptoUtil.mask(null, 4)).isEqualTo("********");
        // 默认 showChars=4，字符串长 12：前 4 "abcd" + ******** + 后 4 "ij12"
        assertThat(CryptoUtil.mask("abcdefghij12")).isEqualTo("abcd********ij12");
    }

    @Test
    void tamperedTokenShouldFail(@TempDir Path tmp) throws Exception {
        CryptoUtil util = newUtil(tmp.resolve(".encryption_key"));
        String token = util.encrypt("secret-payload");
        byte[] raw = Base64.getUrlDecoder().decode(token);
        raw[raw.length - 1] ^= (byte) 0xFF; // 翻转最后一个字节（HMAC 区）
        String bad = Base64.getUrlEncoder().encodeToString(raw);

        assertThatThrownBy(() -> util.decrypt(bad))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidVersionByteShouldFail(@TempDir Path tmp) throws Exception {
        CryptoUtil util = newUtil(tmp.resolve(".encryption_key"));
        // 构造一段最小长度但 version != 0x80 的 base64
        byte[] fake = new byte[1 + 8 + 16 + 32];
        fake[0] = 0x00;
        String bad = Base64.getUrlEncoder().encodeToString(fake);
        assertThatThrownBy(() -> util.decrypt(bad))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 生产保护：密钥文件不存在 + forbidAutoGenerate=true 时，
     * init() 必须直接抛异常，绝不能静默生成新密钥。
     * 这是为了避免线上 Java 启动时意外生成与 Python 不同的密钥。
     */
    @Test
    void forbidAutoGenerate_whenNoKeyFile_throws(@TempDir Path tmp) throws Exception {
        Path nonExistent = tmp.resolve(".encryption_key");
        assertThat(nonExistent).doesNotExist();

        CryptoUtil util = new CryptoUtil();
        java.lang.reflect.Field fPath = CryptoUtil.class.getDeclaredField("keyFilePath");
        fPath.setAccessible(true);
        fPath.set(util, nonExistent.toString());
        java.lang.reflect.Field fForbid = CryptoUtil.class.getDeclaredField("forbidAutoGenerate");
        fForbid.setAccessible(true);
        fForbid.set(util, true);

        assertThatThrownBy(util::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化加密密钥失败")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "密钥文件不存在且已禁用自动生成: " + nonExistent.toAbsolutePath() +
                        "。请将 Python 版的 .encryption_key 拷贝到此路径，或设置 " +
                        "loganalysis.encryption.key-file 指向正确位置。");

        // 确认文件没有被创建
        assertThat(nonExistent).doesNotExist();
    }
}
