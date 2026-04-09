package com.nighthunt.elo.service;

import com.nighthunt.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EloService}.
 *
 * All @Value fields are injected via ReflectionTestUtils – no Spring context
 * needed, so tests run fast.
 */
class EloServiceTest {

    private EloService eloService;

    @BeforeEach
    void setUp() {
        eloService = new EloService();
        // Mirror defaults from application.yml
        ReflectionTestUtils.setField(eloService, "silverThreshold",   1200);
        ReflectionTestUtils.setField(eloService, "goldThreshold",     1400);
        ReflectionTestUtils.setField(eloService, "platinumThreshold", 1600);
        ReflectionTestUtils.setField(eloService, "diamondThreshold",  1800);
        ReflectionTestUtils.setField(eloService, "masterThreshold",   2000);
        ReflectionTestUtils.setField(eloService, "kNew",    40);
        ReflectionTestUtils.setField(eloService, "kNormal", 25);
        ReflectionTestUtils.setField(eloService, "kHigh",   16);
    }

    // ── resolveTier ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ELO 0 → BRONZE")
    void resolveTier_zero_isBronze() {
        assertThat(eloService.resolveTier(0)).isEqualTo("BRONZE");
    }

    @Test
    @DisplayName("ELO 1199 → BRONZE")
    void resolveTier_belowSilver_isBronze() {
        assertThat(eloService.resolveTier(1199)).isEqualTo("BRONZE");
    }

    @Test
    @DisplayName("ELO 1200 → SILVER")
    void resolveTier_atSilver_isSilver() {
        assertThat(eloService.resolveTier(1200)).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("ELO 1399 → SILVER")
    void resolveTier_belowGold_isSilver() {
        assertThat(eloService.resolveTier(1399)).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("ELO 1400 → GOLD")
    void resolveTier_atGold_isGold() {
        assertThat(eloService.resolveTier(1400)).isEqualTo("GOLD");
    }

    @Test
    @DisplayName("ELO 1600 → PLATINUM")
    void resolveTier_atPlatinum_isPlatinum() {
        assertThat(eloService.resolveTier(1600)).isEqualTo("PLATINUM");
    }

    @Test
    @DisplayName("ELO 1800 → DIAMOND")
    void resolveTier_atDiamond_isDiamond() {
        assertThat(eloService.resolveTier(1800)).isEqualTo("DIAMOND");
    }

    @Test
    @DisplayName("ELO 2000 → MASTER")
    void resolveTier_atMaster_isMaster() {
        assertThat(eloService.resolveTier(2000)).isEqualTo("MASTER");
    }

    @Test
    @DisplayName("ELO 9999 → MASTER")
    void resolveTier_aboveMaster_isMaster() {
        assertThat(eloService.resolveTier(9999)).isEqualTo("MASTER");
    }

    // ── calculateDelta ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Win against equal opponent → positive delta")
    void calculateDelta_win_returnsPositive() {
        int delta = eloService.calculateDelta(1000, 1000, 1.0);
        assertThat(delta).isPositive();
    }

    @Test
    @DisplayName("Loss against equal opponent → negative delta")
    void calculateDelta_loss_returnsNegative() {
        int delta = eloService.calculateDelta(1000, 1000, 0.0);
        assertThat(delta).isNegative();
    }

    @Test
    @DisplayName("Draw against equal opponent → delta ~0 (rounds to 0)")
    void calculateDelta_drawEqualElo_returnsZero() {
        int delta = eloService.calculateDelta(1000, 1000, 0.5);
        assertThat(delta).isEqualTo(0);
    }

    @Test
    @DisplayName("Win against much stronger opponent → larger positive delta")
    void calculateDelta_winAgainstStronger_isLargerGain() {
        int deltaVsEqual  = eloService.calculateDelta(1000, 1000, 1.0);
        int deltaVsStrong = eloService.calculateDelta(1000, 2000, 1.0);
        assertThat(deltaVsStrong).isGreaterThan(deltaVsEqual);
    }

    @Test
    @DisplayName("Loss against much weaker opponent → larger negative delta")
    void calculateDelta_lossAgainstWeaker_isLargerPenalty() {
        int deltaVsEqual = eloService.calculateDelta(1000, 1000, 0.0);
        int deltaVsWeak  = eloService.calculateDelta(1000, 200,  0.0);
        assertThat(deltaVsWeak).isLessThan(deltaVsEqual);
    }

    @Test
    @DisplayName("Low-ELO player (< 1200) uses K=40 — full-win delta > 20")
    void calculateDelta_lowElo_highKFactor() {
        // Equal ELO win yields K * 0.5 ≈ 40 * 0.5 = 20
        int delta = eloService.calculateDelta(1000, 1000, 1.0);
        assertThat(delta).isEqualTo(20);
    }

    @Test
    @DisplayName("Normal-ELO player (1200–1800) uses K=25 — full-win delta = 13")
    void calculateDelta_normalElo_normalKFactor() {
        // 1500 is in the normal range; equal ELO win → 25 * 0.5 = 12.5 → rounds to 13
        int delta = eloService.calculateDelta(1500, 1500, 1.0);
        assertThat(delta).isEqualTo(13);
    }

    @Test
    @DisplayName("High-ELO player (> 1800) uses K=16 — full-win delta = 8")
    void calculateDelta_highElo_lowKFactor() {
        // 1900 is above diamond; equal ELO win → 16 * 0.5 = 8
        int delta = eloService.calculateDelta(1900, 1900, 1.0);
        assertThat(delta).isEqualTo(8);
    }

    // ── applyDelta ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("applyDelta(win) increments totalWins and raises ELO")
    void applyDelta_win_incrementsWinsAndElo() {
        User user = User.builder().elo(1000).tier("BRONZE").totalWins(0).totalLosses(0).totalDraws(0).build();
        int delta = eloService.calculateDelta(1000, 1000, 1.0); // +20

        eloService.applyDelta(user, delta);

        assertThat(user.getElo()).isEqualTo(1020);
        assertThat(user.getTotalWins()).isEqualTo(1);
        assertThat(user.getTotalLosses()).isEqualTo(0);
    }

    @Test
    @DisplayName("applyDelta(loss) increments totalLosses and lowers ELO")
    void applyDelta_loss_incrementsLossesAndLowersElo() {
        User user = User.builder().elo(1000).tier("BRONZE").totalWins(0).totalLosses(0).totalDraws(0).build();
        int delta = eloService.calculateDelta(1000, 1000, 0.0); // -20

        eloService.applyDelta(user, delta);

        assertThat(user.getElo()).isEqualTo(980);
        assertThat(user.getTotalLosses()).isEqualTo(1);
    }

    @Test
    @DisplayName("applyDelta(draw) increments totalDraws, no ELO change for equal")
    void applyDelta_draw_incrementsDraws() {
        User user = User.builder().elo(1000).tier("BRONZE").totalWins(0).totalLosses(0).totalDraws(0).build();
        int delta = eloService.calculateDelta(1000, 1000, 0.5); // 0

        eloService.applyDelta(user, delta);

        assertThat(user.getElo()).isEqualTo(1000);
        assertThat(user.getTotalDraws()).isEqualTo(1);
    }

    @Test
    @DisplayName("ELO cannot go below 0 (floor)")
    void applyDelta_veryNegative_clampsToZero() {
        User user = User.builder().elo(5).tier("BRONZE").totalWins(0).totalLosses(0).totalDraws(0).build();
        // Force a large loss
        eloService.applyDelta(user, -100);

        assertThat(user.getElo()).isEqualTo(0);
    }

    @Test
    @DisplayName("applyDelta updates tier when ELO crosses tier boundary")
    void applyDelta_crossesTierBoundary_updatesTier() {
        // Player at 1190 (BRONZE), gain 20 → 1210 → SILVER
        User user = User.builder().elo(1190).tier("BRONZE").totalWins(0).totalLosses(0).totalDraws(0).build();
        eloService.applyDelta(user, 20);

        assertThat(user.getElo()).isEqualTo(1210);
        assertThat(user.getTier()).isEqualTo("SILVER");
    }
}
