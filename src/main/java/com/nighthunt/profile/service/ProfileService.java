package com.nighthunt.profile.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.profile.dto.ProfileResponse;
import com.nighthunt.profile.dto.UpdateCharacterRequest;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;

    // ──────────────────────────────────────────────────────────────────────────

    public ProfileResponse getProfile(Long userId) {
        User user = findUser(userId);
        return toResponse(user);
    }

    /**
     * Public profile - returns a limited view suitable for another player's card.
     */
    public ProfileResponse getPublicProfile(Long targetUserId) {
        User user = findUser(targetUserId);
        log.info("[Profile] Public profile viewed: targetUserId={}", targetUserId);
        return ProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .selectedCharacterId(user.getSelectedCharacterId())
                .elo(user.getElo())
                .tier(user.getTier())
                .totalWins(user.getTotalWins())
                .totalLosses(user.getTotalLosses())
                .coins(0L)
                .platform(null)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Updates the player's selected character.
     *
     * <p>The {@code selectedCharacterId} value (e.g. {@code "character_02"}) comes directly
     * from the Unity client's {@code CharacterDefinition.CharacterId}. There is no separate
     * characters table yet, so we store it as-is.  If a master-data table is introduced later,
     * add a lookup here and throw {@link ErrorCodes#PROFILE_CHARACTER_NOT_FOUND} on miss.
     */
    @Transactional
    public ProfileResponse updateCharacter(Long userId, UpdateCharacterRequest request) {
        User user = findUser(userId);

        user.setSelectedCharacterId(request.getSelectedCharacterId());
        userRepository.save(user);

        log.info("User {} updated selectedCharacterId -> {}", userId, request.getSelectedCharacterId());
        return toResponse(user);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "User not found"));
    }

    private ProfileResponse toResponse(User user) {
        return ProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .selectedCharacterId(user.getSelectedCharacterId())
                .elo(user.getElo())
                .tier(user.getTier())
                .totalWins(user.getTotalWins())
                .totalLosses(user.getTotalLosses())
                .coins(user.getCoins())
                .platform(user.getPlatform())
                .build();
    }
}
