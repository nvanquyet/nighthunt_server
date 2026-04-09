package com.nighthunt.room.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RoomCodeGenerator}.
 */
class RoomCodeGeneratorTest {

    private static final Pattern VALID_CHARS = Pattern.compile("^[A-Z0-9]{8}$");

    @Test
    @DisplayName("Generated code is exactly 8 characters")
    void generate_isEightChars() {
        String code = RoomCodeGenerator.generate();
        assertThat(code).hasSize(8);
    }

    @Test
    @DisplayName("Generated code contains only uppercase letters and digits")
    void generate_containsOnlyValidCharacters() {
        String code = RoomCodeGenerator.generate();
        assertThat(code).matches(VALID_CHARS);
    }

    @RepeatedTest(5)
    @DisplayName("generate() never returns null or blank")
    void generate_isNotNullOrBlank() {
        assertThat(RoomCodeGenerator.generate()).isNotBlank();
    }

    @Test
    @DisplayName("100 consecutive calls produce at least 90 unique codes (randomness check)")
    void generate_producesUniqueValues() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(RoomCodeGenerator.generate());
        }
        // With 36^8 possible values, the chance of fewer than 90 unique in 100 is astronomically low
        assertThat(codes.size()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("All 100 generated codes match valid-character pattern")
    void generate_allMatchPattern() {
        for (int i = 0; i < 100; i++) {
            assertThat(RoomCodeGenerator.generate()).matches(VALID_CHARS);
        }
    }
}
