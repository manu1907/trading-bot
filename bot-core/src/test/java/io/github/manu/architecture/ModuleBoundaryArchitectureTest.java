package io.github.manu.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleBoundaryArchitectureTest {

    @Test
    void core_build_does_not_assemble_concrete_provider_or_strategy_modules() throws IOException {
        String buildFile = Files.readString(Path.of("build.gradle.kts"));

        assertThat(buildFile)
                .doesNotContain("bot-exchange-binance")
                .doesNotContain("bot-strategy-lfa")
                .doesNotContain("id(\"application\")")
                .doesNotContain("id(\"org.springframework.boot\")");
    }
}
