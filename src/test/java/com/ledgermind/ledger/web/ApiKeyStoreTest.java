package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.ledgermind.TestApiKeys;

/** AUTH-6: every stored digest is compared on every call (no early exit), for a first-entry match and for a miss. */
class ApiKeyStoreTest {

    private static ApiKeyStore.Entry entry(String id, String key) {
        return new ApiKeyStore.Entry(id, HexFormat.of().parseHex(TestApiKeys.sha256Hex(key)));
    }

    @Test
    void comparesAgainstEveryStoredKeyWithNoEarlyExit() {
        AtomicInteger comparisons = new AtomicInteger();
        BiPredicate<byte[], byte[]> counting = (a, b) -> {
            comparisons.incrementAndGet();
            return MessageDigest.isEqual(a, b);
        };
        ApiKeyStore store = new ApiKeyStore(
                List.of(entry("first", "k1"), entry("second", "k2"), entry("third", "k3")), counting);

        assertThat(store.authenticate("k1")).hasValue("first");
        assertThat(comparisons.get()).as("match on the FIRST entry").isEqualTo(3);

        comparisons.set(0);
        assertThat(store.authenticate("not-a-key")).isEmpty();
        assertThat(comparisons.get()).as("no match").isEqualTo(3);

        comparisons.set(0);
        assertThat(store.authenticate("k3")).hasValue("third");
        assertThat(comparisons.get()).as("match on the last entry").isEqualTo(3);
    }
}
