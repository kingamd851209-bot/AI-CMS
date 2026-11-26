package com.xinhongru.security.password;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class Sm3PasswordHashService {
    static final String PREFIX = "{SM3}v1$";
    static final int DEFAULT_ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final int iterations;
    private final byte[] pepper;

    public Sm3PasswordHashService() {
        this(System.getenv().getOrDefault("CMS_PASSWORD_PEPPER", ""), DEFAULT_ITERATIONS);
    }

    Sm3PasswordHashService(String pepper, int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
        this.pepper = StringUtils.defaultString(pepper).getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String rawPassword) {
        if (StringUtils.isBlank(rawPassword)) {
            throw new IllegalArgumentException("rawPassword must not be blank");
        }
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] digest = digest(rawPassword, salt, iterations);
        return PREFIX + iterations + "$" + encode(salt) + "$" + encode(digest);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (StringUtils.isBlank(rawPassword) || !isSm3Hash(encodedPassword)) {
            return false;
        }
        String[] parts = encodedPassword.substring(PREFIX.length()).split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            int encodedIterations = Integer.parseInt(parts[0]);
            byte[] salt = decode(parts[1]);
            byte[] expected = decode(parts[2]);
            byte[] actual = digest(rawPassword, salt, encodedIterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public boolean isSm3Hash(String value) {
        return StringUtils.startsWith(value, PREFIX);
    }

    private byte[] digest(String rawPassword, byte[] salt, int digestIterations) {
        byte[] password = rawPassword.getBytes(StandardCharsets.UTF_8);
        byte[] current = sm3(salt, pepper, password);
        for (int index = 1; index < digestIterations; index++) {
            current = sm3(current, salt, pepper, password);
        }
        return current;
    }

    private static byte[] sm3(byte[]... chunks) {
        SM3Digest digest = new SM3Digest();
        for (byte[] chunk : chunks) {
            digest.update(chunk, 0, chunk.length);
        }
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
