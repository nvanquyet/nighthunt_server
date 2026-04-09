package com.nighthunt.gamemode.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.gamemode.dto.GameModeResponse;
import com.nighthunt.gamemode.entity.GameMode;
import com.nighthunt.gamemode.repository.GameModeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GameModeService}.
 * Tests game mode retrieval and configuration.
 */
@ExtendWith(MockitoExtension.class)
class GameModeServiceTest {

    @Mock GameModeRepository gameModeRepository;

    @InjectMocks GameModeService gameModeService;

    private GameMode mode2v2;
    private GameMode mode5v5;
    private GameMode mode1v1;
    private GameMode modeFFA;

    @BeforeEach
    void setUp() {
        mode2v2 = GameMode.builder()
                .id(1L)
                .name("2v2")
                .displayName("Team Deathmatch 2v2")
                .playersPerTeam(2)
                .teamCount(2)
                .enabled(true)
                .build();

        mode5v5 = GameMode.builder()
                .id(2L)
                .name("5v5")
                .displayName("Team Deathmatch 5v5")
                .playersPerTeam(5)
                .teamCount(2)
                .enabled(true)
                .build();

        mode1v1 = GameMode.builder()
                .id(3L)
                .name("1v1")
                .displayName("Duel 1v1")
                .playersPerTeam(1)
                .teamCount(2)
                .enabled(true)
                .build();

        modeFFA = GameMode.builder()
                .id(4L)
                .name("ffa")
                .displayName("Free For All")
                .playersPerTeam(1)
                .teamCount(8)
                .enabled(false)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET ALL GAME MODES
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAllGameModes: returns all enabled modes")
    void getAllGameModes_returnsEnabledOnly() {
        when(gameModeRepository.findByEnabled(true))
                .thenReturn(Arrays.asList(mode2v2, mode5v5, mode1v1));

        List<GameModeResponse> result = gameModeService.getAllGameModes();

        assertThat(result).hasSize(3);
        assertThat(result).extracting("name").containsExactlyInAnyOrder("2v2", "5v5", "1v1");
        assertThat(result).allMatch(mode -> mode.getEnabled());
    }

    @Test
    @DisplayName("getAllGameModes: returns empty list when no enabled modes")
    void getAllGameModes_noEnabledModes_returnsEmpty() {
        when(gameModeRepository.findByEnabled(true)).thenReturn(List.of());

        List<GameModeResponse> result = gameModeService.getAllGameModes();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAllGameModes: disabled mode not included")
    void getAllGameModes_excludesDisabledModes() {
        when(gameModeRepository.findByEnabled(true))
                .thenReturn(Arrays.asList(mode2v2, mode5v5));

        List<GameModeResponse> result = gameModeService.getAllGameModes();

        assertThat(result).hasSize(2);
        assertThat(result).extracting("name").doesNotContain("ffa");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET GAME MODE BY NAME
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getGameModeByName: returns mode when found")
    void getGameModeByName_found_returnsMode() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        GameModeResponse result = gameModeService.getGameModeByName("2v2");

        assertThat(result.getName()).isEqualTo("2v2");
        assertThat(result.getDisplayName()).isEqualTo("Team Deathmatch 2v2");
        assertThat(result.getPlayersPerTeam()).isEqualTo(2);
        assertThat(result.getTeamCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getGameModeByName: throws exception when not found")
    void getGameModeByName_notFound_throwsException() {
        when(gameModeRepository.findByName("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameModeService.getGameModeByName("invalid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Game mode not found");
    }

    @Test
    @DisplayName("getGameModeByName: returns disabled mode (no filtering)")
    void getGameModeByName_disabledMode_returnsMode() {
        when(gameModeRepository.findByName("ffa")).thenReturn(Optional.of(modeFFA));

        GameModeResponse result = gameModeService.getGameModeByName("ffa");

        assertThat(result.getName()).isEqualTo("ffa");
        assertThat(result.getEnabled()).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET PLAYERS PER TEAM
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getPlayersPerTeam: returns correct value for 2v2")
    void getPlayersPerTeam_2v2_returns2() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        int result = gameModeService.getPlayersPerTeam("2v2");

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("getPlayersPerTeam: returns correct value for 5v5")
    void getPlayersPerTeam_5v5_returns5() {
        when(gameModeRepository.findByName("5v5")).thenReturn(Optional.of(mode5v5));

        int result = gameModeService.getPlayersPerTeam("5v5");

        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("getPlayersPerTeam: returns correct value for 1v1")
    void getPlayersPerTeam_1v1_returns1() {
        when(gameModeRepository.findByName("1v1")).thenReturn(Optional.of(mode1v1));

        int result = gameModeService.getPlayersPerTeam("1v1");

        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("getPlayersPerTeam: throws exception when mode not found")
    void getPlayersPerTeam_notFound_throwsException() {
        when(gameModeRepository.findByName("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameModeService.getPlayersPerTeam("invalid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Game mode not found");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET TEAM COUNT
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getTeamCount: returns 2 for team deathmatch modes")
    void getTeamCount_teamDeathmatch_returns2() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        int result = gameModeService.getTeamCount("2v2");

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("getTeamCount: returns 8 for FFA mode")
    void getTeamCount_ffa_returns8() {
        when(gameModeRepository.findByName("ffa")).thenReturn(Optional.of(modeFFA));

        int result = gameModeService.getTeamCount("ffa");

        assertThat(result).isEqualTo(8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IS MODE ENABLED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isModeEnabled: returns true when mode enabled")
    void isModeEnabled_enabled_returnsTrue() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        boolean result = gameModeService.isModeEnabled("2v2");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isModeEnabled: returns false when mode disabled")
    void isModeEnabled_disabled_returnsFalse() {
        when(gameModeRepository.findByName("ffa")).thenReturn(Optional.of(modeFFA));

        boolean result = gameModeService.isModeEnabled("ffa");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isModeEnabled: returns false when mode not found")
    void isModeEnabled_notFound_returnsFalse() {
        when(gameModeRepository.findByName("invalid")).thenReturn(Optional.empty());

        boolean result = gameModeService.isModeEnabled("invalid");

        assertThat(result).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDATE MODE FOR PARTY SIZE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateModeForPartySize: valid party size for 2v2")
    void validateModeForPartySize_valid_noException() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        // Should not throw
        assertThatCode(() -> gameModeService.validateModeForPartySize("2v2", 2))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateModeForPartySize: party size exceeds mode capacity")
    void validateModeForPartySize_tooLarge_throwsException() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        assertThatThrownBy(() -> gameModeService.validateModeForPartySize("2v2", 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party size exceeds mode capacity");
    }

    @Test
    @DisplayName("validateModeForPartySize: single player always valid")
    void validateModeForPartySize_singlePlayer_valid() {
        when(gameModeRepository.findByName("5v5")).thenReturn(Optional.of(mode5v5));

        assertThatCode(() -> gameModeService.validateModeForPartySize("5v5", 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateModeForPartySize: full team valid")
    void validateModeForPartySize_fullTeam_valid() {
        when(gameModeRepository.findByName("5v5")).thenReturn(Optional.of(mode5v5));

        assertThatCode(() -> gameModeService.validateModeForPartySize("5v5", 5))
                .doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET TOTAL PLAYERS FOR MODE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getTotalPlayersForMode: 2v2 returns 4 players")
    void getTotalPlayersForMode_2v2_returns4() {
        when(gameModeRepository.findByName("2v2")).thenReturn(Optional.of(mode2v2));

        int result = gameModeService.getTotalPlayersForMode("2v2");

        assertThat(result).isEqualTo(4); // 2 players × 2 teams
    }

    @Test
    @DisplayName("getTotalPlayersForMode: 5v5 returns 10 players")
    void getTotalPlayersForMode_5v5_returns10() {
        when(gameModeRepository.findByName("5v5")).thenReturn(Optional.of(mode5v5));

        int result = gameModeService.getTotalPlayersForMode("5v5");

        assertThat(result).isEqualTo(10); // 5 players × 2 teams
    }

    @Test
    @DisplayName("getTotalPlayersForMode: FFA returns 8 players")
    void getTotalPlayersForMode_ffa_returns8() {
        when(gameModeRepository.findByName("ffa")).thenReturn(Optional.of(modeFFA));

        int result = gameModeService.getTotalPlayersForMode("ffa");

        assertThat(result).isEqualTo(8); // 1 player × 8 teams (FFA)
    }

    @Test
    @DisplayName("getTotalPlayersForMode: throws exception when mode not found")
    void getTotalPlayersForMode_notFound_throwsException() {
        when(gameModeRepository.findByName("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameModeService.getTotalPlayersForMode("invalid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Game mode not found");
    }
}
