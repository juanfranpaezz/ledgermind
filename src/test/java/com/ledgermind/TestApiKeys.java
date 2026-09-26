package com.ledgermind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Test-only API key. The key is generated at run time (SecureRandom); only its SHA-256 line is written, to a temp
 * keys file outside the repository. No key is committed.
 *
 * <p>Registered as an {@link EnvironmentPostProcessor} in {@code src/test/resources/META-INF/spring.factories}, so
 * every Spring Boot test context starts with {@code LEDGERMIND_API_KEYS_FILE} pointing at that file, added LAST (a
 * real environment variable or a test's own property wins). It supplies the file only; the production store stays
 * fail-closed and every check still runs.
 */
public class TestApiKeys implements EnvironmentPostProcessor {

    public static final String KEY_ID = "test-client";
    public static final String PROPERTY = "LEDGERMIND_API_KEYS_FILE";

    private static final String KEY;
    private static final Path FILE;

    static {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        KEY = "lmk_" + HexFormat.of().formatHex(raw);
        try {
            FILE = Files.createTempFile("ledgermind-test-keys-", ".txt");
            Files.writeString(FILE, "# generated at test run time by TestApiKeys\n"
                    + KEY_ID + " sha256:" + sha256Hex(KEY) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The plaintext test key (lives only in this JVM). */
    public static String key() {
        return KEY;
    }

    /** Forward-slash absolute path of the temp keys file (safe inside properties strings on Windows). */
    public static String file() {
        return FILE.toAbsolutePath().toString().replace('\\', '/');
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.containsProperty(PROPERTY)) {
            environment.getPropertySources().addLast(
                    new MapPropertySource("ledgermindTestApiKeys", Map.of(PROPERTY, file())));
        }
    }
}
