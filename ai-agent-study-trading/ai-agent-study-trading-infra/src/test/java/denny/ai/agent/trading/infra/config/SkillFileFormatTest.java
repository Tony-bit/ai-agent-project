package denny.ai.agent.trading.infra.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFileFormatTest {

    private static final List<String> EXPECTED_SKILL_FILES = List.of(
            ".claude/skills/trading/get-stock-info/SKILL.md",
            ".claude/skills/trading/get-historical-bars/SKILL.md",
            ".claude/skills/trading/get-technical-indicators/SKILL.md",
            ".claude/skills/trading/get-fundamental-data/SKILL.md",
            ".claude/skills/trading/get-sentiment/SKILL.md",
            ".claude/skills/trading/get-stock-news/SKILL.md",
            ".claude/skills/trading/search-stock-by-name/SKILL.md"
    );

    @Test
    void allExpectedTradingSkillFilesExist() {
        for (String path : EXPECTED_SKILL_FILES) {
            ClassPathResource resource = new ClassPathResource(path);
            assertTrue(resource.exists(), "Missing skill file: " + path);
        }
    }

    @Test
    void everySkillFileHasRequiredFrontmatterAndToolReference() throws IOException {
        for (String path : EXPECTED_SKILL_FILES) {
            ClassPathResource resource = new ClassPathResource(path);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(content.startsWith("---"), "Skill file should start with frontmatter: " + path);
            assertTrue(content.contains("\nname:"), "Skill file should declare name: " + path);
            assertTrue(content.contains("\ndescription:"), "Skill file should declare description: " + path);
            assertTrue(content.contains("ToolCallback (`TradingToolCallbacks`)"),
                    "Skill file should reference TradingToolCallbacks: " + path);
            assertFalse(content.isBlank(), "Skill file should not be blank: " + path);
        }
    }

    @Test
    void everySkillFileUsesDistinctNameMetadata() throws IOException {
        java.util.Set<String> names = new java.util.HashSet<>();

        for (String path : EXPECTED_SKILL_FILES) {
            ClassPathResource resource = new ClassPathResource(path);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            String nameLine = content.lines()
                    .filter(line -> line.startsWith("name:"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(nameLine, "Skill file should contain name metadata: " + path);
            assertTrue(names.add(nameLine.trim()), "Duplicate skill metadata name: " + nameLine);
        }
    }
}
