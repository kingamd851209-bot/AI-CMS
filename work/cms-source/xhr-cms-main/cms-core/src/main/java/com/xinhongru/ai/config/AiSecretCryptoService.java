package com.xinhongru.ai.config;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AiSecretCryptoService {
    public static final String MASTER_KEY_NAME = "CMS_AI_SECRET_MASTER_KEY";
    private static final String PROVIDER_NAME = "BC";
    private static final String GCM_TRANSFORMATION = "SM4/GCM/NoPadding";
    private static final String CBC_TRANSFORMATION = "SM4/CBC/PKCS5Padding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String GCM_PREFIX = "sm4-gcm-v1:";
    private static final String CBC_HMAC_PREFIX = "sm4-cbc-hmac-v1:";
    private static final String SM4_KEY_CONTEXT = "cms-ai-provider-sm4-key-v1";
    private static final String HMAC_KEY_CONTEXT = "cms-ai-provider-hmac-key-v1";
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean allowEnvironmentMasterKey;
    private final String configuredMasterKey;
    private final String environmentMasterKeyName;

    static {
        if (Security.getProvider(PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public AiSecretCryptoService() {
        this(true, null, MASTER_KEY_NAME);
    }

    @Autowired
    public AiSecretCryptoService(Environment environment) {
        this(
                true,
                environment.getProperty("cms.ai.secret-master-key"),
                environment.getProperty("cms.ai.secret-master-key-env", MASTER_KEY_NAME));
    }

    AiSecretCryptoService(boolean allowEnvironmentMasterKey) {
        this(allowEnvironmentMasterKey, null, MASTER_KEY_NAME);
    }

    AiSecretCryptoService(
            boolean allowEnvironmentMasterKey,
            String configuredMasterKey,
            String environmentMasterKeyName) {
        this.allowEnvironmentMasterKey = allowEnvironmentMasterKey;
        this.configuredMasterKey = configuredMasterKey;
        this.environmentMasterKeyName =
                environmentMasterKeyName == null || environmentMasterKeyName.isBlank()
                        ? MASTER_KEY_NAME
                        : environmentMasterKeyName;
    }

    public CipherText encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            return encryptGcm(plainText);
        } catch (GeneralSecurityException ex) {
            return encryptCbcHmac(plainText);
        }
    }

    private CipherText encryptGcm(String plainText) throws GeneralSecurityException {
        byte[] nonce = randomNonce(12);
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION, PROVIDER_NAME);
        cipher.init(Cipher.ENCRYPT_MODE, sm4KeySpec(), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return new CipherText(
                GCM_PREFIX + Base64.getEncoder().encodeToString(encrypted),
                Base64.getEncoder().encodeToString(nonce));
    }

    private CipherText encryptCbcHmac(String plainText) {
        byte[] nonce = new byte[16];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CBC_TRANSFORMATION, PROVIDER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, sm4KeySpec(), new IvParameterSpec(nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] mac = hmac(nonce, encrypted);
            byte[] payload = new byte[encrypted.length + mac.length];
            System.arraycopy(encrypted, 0, payload, 0, encrypted.length);
            System.arraycopy(mac, 0, payload, encrypted.length, mac.length);
            return new CipherText(
                    CBC_HMAC_PREFIX + Base64.getEncoder().encodeToString(payload),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AI provider secret encryption failed", ex);
        }
    }

    public String decrypt(String cipherText, String nonce) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (cipherText.startsWith(GCM_PREFIX)) {
            return decryptGcm(cipherText.substring(GCM_PREFIX.length()), nonce);
        }
        if (cipherText.startsWith(CBC_HMAC_PREFIX)) {
            return decryptCbcHmac(cipherText.substring(CBC_HMAC_PREFIX.length()), nonce);
        }
        throw new IllegalStateException("AI provider secret decryption failed");
    }

    private String decryptGcm(String cipherText, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION, PROVIDER_NAME);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    sm4KeySpec(),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(nonce)));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(cipherText));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("AI provider secret decryption failed", ex);
        }
    }

    private String decryptCbcHmac(String cipherText, String nonce) {
        try {
            byte[] nonceBytes = Base64.getDecoder().decode(nonce);
            byte[] payload = Base64.getDecoder().decode(cipherText);
            if (payload.length <= 32) {
                throw new GeneralSecurityException("invalid cipher payload");
            }
            byte[] encrypted = Arrays.copyOf(payload, payload.length - 32);
            byte[] actualMac = Arrays.copyOfRange(payload, payload.length - 32, payload.length);
            byte[] expectedMac = hmac(nonceBytes, encrypted);
            if (!MessageDigest.isEqual(expectedMac, actualMac)) {
                throw new GeneralSecurityException("invalid cipher authentication tag");
            }
            Cipher cipher = Cipher.getInstance(CBC_TRANSFORMATION, PROVIDER_NAME);
            cipher.init(Cipher.DECRYPT_MODE, sm4KeySpec(), new IvParameterSpec(nonceBytes));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("AI provider secret decryption failed", ex);
        }
    }

    private SecretKeySpec sm4KeySpec() {
        return new SecretKeySpec(deriveKey(SM4_KEY_CONTEXT, 16), "SM4");
    }

    private byte[] hmac(byte[] nonce, byte[] encrypted) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(deriveKey(HMAC_KEY_CONTEXT, 32), HMAC_ALGORITHM));
        mac.update(nonce);
        mac.update(encrypted);
        return mac.doFinal();
    }

    private byte[] deriveKey(String context, int bytes) {
        String masterKey = System.getProperty(MASTER_KEY_NAME);
        if (masterKey == null || masterKey.isBlank()) {
            masterKey = configuredMasterKey;
        }
        if ((masterKey == null || masterKey.isBlank()) && allowEnvironmentMasterKey) {
            masterKey = System.getenv(environmentMasterKeyName);
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    MASTER_KEY_NAME + " is required to save AI provider API keys");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Arrays.copyOf(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)), bytes);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AI provider secret key initialization failed", ex);
        }
    }

    private byte[] randomNonce(int length) {
        byte[] nonce = new byte[length];
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    public record CipherText(String cipher, String nonce) {}
}
