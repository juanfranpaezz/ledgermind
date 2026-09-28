package com.ledgermind.ledger.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * API keys for {@code /api}: the file named
 * by {@code LEDGERMIND_API_KEYS_FILE} holds one {@code <key_id> sha256:<64 lowercase hex>} line per key; blank lines
 * and {@code #} comments are skipped. Keys never live in the repository, only their SHA-256.
 *
 * <p>Fail-closed at startup: unset variable, missing or unreadable file, malformed line or repeated key_id stop the
 * application context. An existing EMPTY file is valid and means "no keyed callers" (only the demo allow-list, if
 * any, is reachable). No message ever echoes a line of the file or a presented key.
 */
@Component
public class ApiKeyStore {

    static final String ENV = "LEDGERMIND_API_KEYS_FILE";
    private static final Pattern LINE = Pattern.compile("^([A-Za-z0-9_.-]{1,64}) sha256:([0-9a-f]{64})$");

    record Entry(String keyId, byte[] digest) {
    }

    private final List<Entry> entries;
    private final BiPredicate<byte[], byte[]> digestEquals;

    @Autowired
    ApiKeyStore(@Value("${" + ENV + ":}") String keysFile) {
        this(load(keysFile), MessageDigest::isEqual);
    }

    /** Test seam: the comparator is injectable so a test can count comparisons (constant-time, no early exit). */
    ApiKeyStore(List<Entry> entries, BiPredicate<byte[], byte[]> digestEquals) {
        this.entries = List.copyOf(entries);
        this.digestEquals = digestEquals;
    }

    static List<Entry> load(String keysFile) {
        if (keysFile == null || keysFile.isBlank()) {
            throw new IllegalStateException(ENV + " is not set; /api refuses to start without an API keys file"
                    + " (fail-closed). An existing empty file is valid and means: no keyed callers.");
        }
        String text;
        try {
            text = Files.readString(Path.of(keysFile), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException(ENV + " points at a file that does not exist (fail-closed).");
        } catch (IOException | InvalidPathException e) {
            throw new IllegalStateException(ENV + " could not be read (" + e.getClass().getSimpleName()
                    + "; fail-closed).");
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw new IllegalStateException(ENV + ": line " + (i + 1) + " is not '<key_id> sha256:<64 lowercase"
                        + " hex>'. The line is not echoed because it may hold a plaintext key (fail-closed).");
            }
            if (!seen.add(m.group(1))) {
                throw new IllegalStateException(ENV + ": line " + (i + 1) + " repeats a key_id (fail-closed).");
            }
            out.add(new Entry(m.group(1), HexFormat.of().parseHex(m.group(2))));
        }
        return out;
    }

    /**
     * The key_id of the presented key, or empty. The SHA-256 of the presented value is compared against EVERY stored
     * digest with a constant-time comparison and no early exit, so timing does not reveal which entry matched.
     */
    public Optional<String> authenticate(String presentedKey) {
        byte[] digest = sha256(presentedKey);
        String matched = null;
        for (Entry e : entries) {
            if (digestEquals.test(digest, e.digest()) && matched == null) {
                matched = e.keyId();
            }
        }
        return Optional.ofNullable(matched);
    }

    public int size() {
        return entries.size();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
