package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ledgermind.TestApiKeys;

/**
 * AUTH-3: the application context refuses to start without a usable keys file (4 cases) and starts with a valid or
 * an EMPTY one (2 cases). Hashes are computed at run time; no key line is committed.
 */
class ApiKeyStoreFailClosedTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ApiKeyStore.class);

    @TempDir
    Path dir;

    private ApplicationContextRunner withFile(Path file) {
        return runner.withPropertyValues(
                ApiKeyStore.ENV + "=" + file.toAbsolutePath().toString().replace('\\', '/'));
    }

    private Path file(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static String line(String id, String key) {
        return id + " sha256:" + TestApiKeys.sha256Hex(key) + "\n";
    }

    @Test
    void unsetVariableFailsToStart() {
        assertThat(System.getenv(ApiKeyStore.ENV)).as("precondition: variable not set on this machine").isNull();
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void missingFileFailsToStart() {
        withFile(dir.resolve("absent.txt")).run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining("does not exist"));
    }

    @Test
    void malformedLineFailsToStartWithoutEchoingIt() throws Exception {
        Path f = file("malformed.txt", "client-a sha256:NOT-HEX-plaintext-looking-value\n");
        withFile(f).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().hasMessageContaining("line 1")
                    .hasMessageNotContaining("NOT-HEX-plaintext-looking-value");
        });
    }

    @Test
    void duplicateKeyIdFailsToStart() throws Exception {
        Path f = file("dup.txt", line("client-a", "k1") + line("client-a", "k2"));
        withFile(f).run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining("repeats a key_id"));
    }

    @Test
    void validFileStartsWithItsKeys() throws Exception {
        Path f = file("valid.txt", "# comment\n\n" + line("client-a", "k1") + line("client-b", "k2"));
        withFile(f).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ApiKeyStore store = ctx.getBean(ApiKeyStore.class);
            assertThat(store.size()).isEqualTo(2);
            assertThat(store.authenticate("k2")).hasValue("client-b");
            assertThat(store.authenticate("k3")).isEmpty();
        });
    }

    @Test
    void emptyFileStartsWithNoKeyedCallers() throws Exception {
        Path f = file("empty.txt", "");
        withFile(f).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ApiKeyStore.class).size()).isZero();
            assertThat(ctx.getBean(ApiKeyStore.class).authenticate("anything")).isEmpty();
        });
    }
}
