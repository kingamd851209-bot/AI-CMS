package com.xinhongru.utils;

import com.xinhongru.constant.WebConst;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * @Description @ClassName RsaUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/08/13 23:22
 */
@Slf4j
public class RsaUtils {
    public static String PUBLIC_KEY;
    public static String PRIVATE_KEY;

    public static RSAPublicKey rsaPublicKey;
    public static RSAPrivateKey rsaPrivateKey;

    /** 生成秘钥对 */
    public static void generateKey() {
        // 1.初始化秘钥
        KeyPairGenerator keyPairGenerator;
        try {
            keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            // 随机数生成器
            SecureRandom sr = new SecureRandom();
            // 设置1024位长的秘钥
            keyPairGenerator.initialize(2048, sr);
            // 开始创建
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            // 获取公钥
            rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
            // 获取私钥
            rsaPrivateKey = (RSAPrivateKey) keyPair.getPrivate();
            // 对公钥进行编码
            PUBLIC_KEY = Base64.getEncoder().encodeToString(rsaPublicKey.getEncoded());
            // 对私钥进行编码
            PRIVATE_KEY = Base64.getEncoder().encodeToString(rsaPrivateKey.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    /**
     * 私钥匙加密或解密
     *
     * @param content 加解密内容
     * @param privateKeyStr 私钥
     * @param opmode 加密 Cipher.ENCRYPT_MODE 解密 Cipher.DECRYPT_MODE
     * @return
     */
    private static String encryptByPrivateKey(String content, String privateKeyStr, int opmode) {
        // 私钥要用PKCS8进行处理
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec =
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyStr));
        KeyFactory keyFactory;
        PrivateKey privateKey;

        Cipher cipher;
        String text = null;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            // 还原Key对象
            privateKey = keyFactory.generatePrivate(pkcs8EncodedKeySpec);
            cipher = Cipher.getInstance("RSA");
            cipher.init(opmode, privateKey);
            // 加密解密
            text = encryptTxt(opmode, cipher, content);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return text;
    }

    /**
     * 私钥加密
     *
     * @param content 需要的加密内容
     * @param privateKeyStr 私钥
     * @return 加密结果
     */
    public static String encryptByPrivateKey(String content, String privateKeyStr) {
        return encryptByPrivateKey(content, privateKeyStr, Cipher.ENCRYPT_MODE);
    }

    /**
     * 私钥加密
     *
     * @param content 需要的加密内容
     * @return 加密结果
     */
    public static String encryptByPrivateKey(String content) {
        return encryptByPrivateKey(content, WebConst.RSA_PRIVATE_KEY);
    }

    /**
     * 私钥解密
     *
     * @param content 密文
     * @param privateKeyStr 私钥
     * @return 解密结果
     */
    public static String decryptByPrivateKey(String content, String privateKeyStr) {
        return encryptByPrivateKey(content, privateKeyStr, Cipher.DECRYPT_MODE);
    }

    /**
     * 私钥解密
     *
     * @param content 密文
     * @return 解密结果
     */
    public static String decryptByPrivateKey(String content) {
        return decryptByPrivateKey(content, WebConst.RSA_PRIVATE_KEY);
    }

    /**
     * 加密解密字符串 未使用
     *
     * @param opmode 加密 Cipher.ENCRYPT_MODE 解密 Cipher.DECRYPT_MODE
     * @param cipher
     * @param content 加解密内容
     * @return
     */
    @Deprecated
    private static String encryptTxt(int opmode, Cipher cipher, String content) {
        byte[] result;
        String text = null;
        try {
            if (opmode == Cipher.ENCRYPT_MODE) {
                // 加密
                result = cipher.doFinal(content.getBytes());
                text = Base64.getEncoder().encodeToString(result);
            } else if (opmode == Cipher.DECRYPT_MODE) {
                // 解密
                result = cipher.doFinal(Base64.getDecoder().decode(content));
                text = new String(result, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("RSA 加解密出现异常,异常明细:{}", e.getMessage());
        }
        return text;
    }

    /**
     * 公钥匙加密或解密
     *
     * @param content 加解密内容
     * @param publicKeyStr 公钥
     * @param opmode 加密 Cipher.ENCRYPT_MODE 解密 Cipher.DECRYPT_MODE
     * @return
     */
    public static String encryptByPublicKey(String content, String publicKeyStr, int opmode) {
        // 公钥要用X509进行处理
        X509EncodedKeySpec x509EncodedKeySpec =
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyStr));
        KeyFactory keyFactory;
        PublicKey publicKey;
        Cipher cipher;
        String text = null;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            // 还原Key对象
            publicKey = keyFactory.generatePublic(x509EncodedKeySpec);
            cipher = Cipher.getInstance("RSA");
            cipher.init(opmode, publicKey);
            text = encryptTxt(opmode, cipher, content);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return text;
    }

    /**
     * 公钥加密
     *
     * @param content 需要的加密内容
     * @param publicKeyStr 公钥
     * @return 加密结果
     */
    public static String encryptByPublicKey(String content, String publicKeyStr) {
        return encryptByPublicKey(content, publicKeyStr, Cipher.ENCRYPT_MODE);
    }

    /**
     * 公钥加密
     *
     * @param content 需要的加密内容
     * @return 加密结果
     */
    public static String encryptByPublicKey(String content) {
        return encryptByPublicKey(content, WebConst.RSA_PUBLIC_KEY);
    }

    /**
     * 公钥解密
     *
     * @param content 密文
     * @param publicKeyStr 公钥
     * @return 解密结果
     */
    public static String decryptByPublicKey(String content, String publicKeyStr) {
        return encryptByPublicKey(content, publicKeyStr, Cipher.DECRYPT_MODE);
    }

    /**
     * 公钥解密
     *
     * @param content 密文
     * @return 解密结果
     */
    public static String decryptByPublicKey(String content) {
        return decryptByPublicKey(content, WebConst.RSA_PUBLIC_KEY);
    }
}
