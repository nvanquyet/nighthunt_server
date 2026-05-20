package com.nighthunt.match.service;

import com.nighthunt.match.entity.UserAbandonRecord;
import com.nighthunt.match.repository.UserAbandonRecordRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Applies ELO penalties for mid-match AFK / intentional abandonment and
 * persists a {@link UserAbandonRecord} for auditing and repeat-offender detection.
 *
 * <h3>Penalty policy</h3>
 * <ul>
 *   <li>Default ELO penalty: {@code matchmaking.penalty.abandon-elo} (default −25)</li>
 *   <li>ELO floor: 0 (cannot go negative)</li>
 *   <li>Penalty is skipped if the match had not yet started (no DS allocated),
 *       detected by {@code matchId == null}.</li>
 * </ul>
 *
 * <h3>Repeat-offender escalation</h3>
 * Future work: count abandons in a rolling 7-day window and double penalty after N offences.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbandonPenaltyService {

    private final UserAbandonRecordRepository abandonRecordRepository;
    private final UserRepository              userRepository;

    /** ELO deducted per abandon. Configurable; negative value applied to user ELO. */
    @Value("${matchmaking.penalty.abandon-elo:25}")
    private int abandonEloPenalty;

    /**
     * Record an abandon event and apply the ELO penalty to the user.
     *
     * @param userId  the player who abandoned
     * @param matchId the match string ID (Room.matchId)
     * @param roomId  the room's DB id
     * @param reason  the abandon reason (AFK_TIMEOUT | INTENTIONAL_LEAVE | etc.)
     */
    @Transactional
    public void applyPenalty(Long userId, String matchId, Long roomId, String reason) {
        if (userId == null || matchId == null || matchId.isBlank()) {
            log.warn("[AbandonPenalty] Skipping penalty — null userId or matchId (userId={}, matchId={})", userId, matchId);
            return;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("[AbandonPenalty] User not found: userId={}", userId);
            return;
        }

        int eloBefore = user.getElo();
        int eloChange = -Math.abs(abandonEloPenalty); // always negative
        int eloAfter  = Math.max(0, eloBefore + eloChange);

        // Do not double-penalise if the same user-match pair already has a record
        boolean alreadyRecorded = abandonRecordRepository.findByMatchId(matchId)
                .stream()
                .anyMatch(r -> r.getUserId().equals(userId));
        if (alreadyRecorded) {
            log.debug("[AbandonPenalty] Already recorded for userId={} matchId={} — skipping", userId, matchId);
            return;
        }

        // Persist the abandon record
        UserAbandonRecord record = UserAbandonRecord.builder()
                .userId(userId)
                .matchId(matchId)
                .roomId(roomId != null ? roomId : 0L)
                .reason(reason)
                .eloBefore(eloBefore)
                .eloChange(eloChange)
                .build();
        abandonRecordRepository.save(record);

        // Apply ELO deduction
        user.setElo(eloAfter);
        userRepository.save(user);

        // Repeat-offender check (for future escalation)
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        long recentCount = abandonRecordRepository.countRecentAbandons(userId, sevenDaysAgo);
        if (recentCount >= 3) {
            log.warn("[AbandonPenalty] Repeat offender: userId={} has {} abandons in last 7 days",
                    userId, recentCount);
            // TODO: escalate penalty, issue warning notification, or temp-ban
        }

        log.info("[AbandonPenalty] Applied: userId={} matchId={} reason={} elo: {}→{} (change={})",
                userId, matchId, reason, eloBefore, eloAfter, eloChange);
    }
}
