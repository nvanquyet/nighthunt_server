package com.nighthunt.elo.service;

import com.nighthunt.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * BE-29 — ELO Rating & Tier Assignment Service.
 *
 * <h3>ELO formula</h3>
 * <pre>
 *   Expected score: E = 1 / (1 + 10^((opponentElo - playerElo) / 400))
 *   New ELO:        newElo = oldElo + K * (actualScore - E)
 *   actualScore:    1.0 = win, 0.5 = draw, 0.0 = loss
 * </pre>
 *
 * <p>The K-factor is adaptive: lower-ELO / new players use a higher K so they
 * can rise faster; high-ELO veterans use a lower K for stability.</p>
 *
 * <h3>Tier thresholds (configurable via application.properties)</h3>
 * <pre>
 *   BRONZE   < 1200
 *   SILVER   1200 – 1399
 *   GOLD     1400 – 1599
 *   PLATINUM 1600 – 1799
 *   DIAMOND  1800 – 1999
 *   MASTER   ≥ 2000
 * </pre>
 */
@Slf4j
@Service
public class EloService {

    // ── Thresholds (configurable) ─────────────────────────────────────────────
    @Value("${elo.tier.silver:1200}")   private int silverThreshold;
    @Value("${elo.tier.gold:1400}")     private int goldThreshold;
    @Value("${elo.tier.platinum:1600}") private int platinumThreshold;
    @Value("${elo.tier.diamond:1800}")  private int diamondThreshold;
    @Value("${elo.tier.master:2000}")   private int masterThreshold;

    // ── K-factors ─────────────────────────────────────────────────────────────
    @Value("${elo.k.new:40}")     private int kNew;     // < 1200 ELO
    @Value("${elo.k.normal:25}")  private int kNormal;  // 1200–1800 ELO
    @Value("${elo.k.high:16}")    private int kHigh;    // > 1800 ELO

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calculate ELO change for a player in a standard 1v1 or team match.
     *
     * <p>For team matches, pass the <em>average ELO of the opposing team</em>
     * as {@code opponentElo}.</p>
     *
     * @param playerElo   current ELO of this player
     * @param opponentElo average ELO of the opponent(s)
     * @param result      1.0 = win, 0.5 = draw, 0.0 = loss
     * @return delta to add to the player's ELO (may be negative)
     */
    public int calculateDelta(int playerElo, double opponentElo, double result) {
        double expected = expectedScore(playerElo, opponentElo);
        int k = kFactor(playerElo);
        int delta = (int) Math.round(k * (result - expected));
        log.debug("[ELO] playerElo={} opponentElo={} result={} expected={:.3f} K={} delta={}",
                playerElo, opponentElo, result, expected, k, delta);
        return delta;
    }

    /**
     * Apply ELO delta to a user and update their tier. Does not persist — caller
     * must call {@code userRepository.save(user)}.
     *
     * <p>W/L/D counters are incremented based on {@code actualScore} (the real match
     * outcome), <em>not</em> on the sign of the ELO delta. This avoids incorrectly
     * counting a draw as a win/loss when the player's ELO is far from the opponent's.</p>
     *
     * @param actualScore 1.0 = win, 0.5 = draw, 0.0 = loss
     * @return the ELO change applied (after floor-at-0)
     */
    public int applyDelta(User user, int delta, double actualScore) {
        int before = user.getElo();
        int after  = Math.max(0, before + delta);   // floor at 0
        user.setElo(after);
        user.setTier(resolveTier(after));

        if (actualScore >= 1.0)      user.setTotalWins(user.getTotalWins() + 1);
        else if (actualScore <= 0.0) user.setTotalLosses(user.getTotalLosses() + 1);
        else                         user.setTotalDraws(user.getTotalDraws() + 1);

        log.info("[ELO] User {} ELO {} → {} (Δ{}) tier={}",
                user.getId(), before, after, delta, user.getTier());
        return after - before; // actual change after floor
    }

    /**
     * Resolve tier label for a given ELO value.
     */
    public String resolveTier(int elo) {
        if (elo >= masterThreshold)   return "MASTER";
        if (elo >= diamondThreshold)  return "DIAMOND";
        if (elo >= platinumThreshold) return "PLATINUM";
        if (elo >= goldThreshold)     return "GOLD";
        if (elo >= silverThreshold)   return "SILVER";
        return "BRONZE";
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private double expectedScore(double playerElo, double opponentElo) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentElo - playerElo) / 400.0));
    }

    private int kFactor(int elo) {
        if (elo < silverThreshold) return kNew;
        if (elo > diamondThreshold) return kHigh;
        return kNormal;
    }
}
