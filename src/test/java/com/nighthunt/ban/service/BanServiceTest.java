package com.nighthunt.ban.service;

import com.nighthunt.ban.entity.Ban;
import com.nighthunt.ban.entity.FailedLoginAttempt;
import com.nighthunt.ban.repository.*;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BanService}.
 */
@ExtendWith(MockitoExtension.class)
class BanServiceTest {

    @Mock BanRepository                   banRepository;
    @Mock FailedLoginAttemptRepository    failedLoginAttemptRepository;
    @Mock ConcurrentLoginAttemptRepository concurrentLoginAttemptRepository;
    @Mock BanConfigRepository             banConfigRepository;

    @InjectMocks BanService banService;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Ban activeBan(String reason) {
        Ban b = new Ban();
        b.setId(1L);
        b.setReason(reason);
        b.setIsActive(true);
        b.setExpiresAt(LocalDateTime.now().plusHours(1));
        return b;
    }

    // ── checkUserBan ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkUserBan: user not banned → no exception")
    void checkUserBan_notBanned_noException() {
        when(banRepository.findActiveBanByUserId(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThatCode(() -> banService.checkUserBan(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkUserBan: user banned → BusinessException AUTH_010")
    void checkUserBan_banned_throws() {
        when(banRepository.findActiveBanByUserId(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeBan("Cheating")));

        assertThatThrownBy(() -> banService.checkUserBan(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_ACCOUNT_BANNED);
    }

    // ── checkIpBan ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkIpBan: IP not banned → no exception")
    void checkIpBan_notBanned_noException() {
        when(banRepository.findActiveBanByIpAddress(eq("1.2.3.4"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThatCode(() -> banService.checkIpBan("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkIpBan: IP banned → BusinessException AUTH_011")
    void checkIpBan_banned_throws() {
        when(banRepository.findActiveBanByIpAddress(eq("1.2.3.4"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeBan("Spam")));

        assertThatThrownBy(() -> banService.checkIpBan("1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_IP_BANNED);
    }

    // ── checkDeviceBan ────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkDeviceBan: empty fingerprint → skips check")
    void checkDeviceBan_emptyFingerprint_skips() {
        assertThatCode(() -> banService.checkDeviceBan("")).doesNotThrowAnyException();
        verifyNoInteractions(banRepository);
    }

    @Test
    @DisplayName("checkDeviceBan: null fingerprint → skips check")
    void checkDeviceBan_nullFingerprint_skips() {
        assertThatCode(() -> banService.checkDeviceBan(null)).doesNotThrowAnyException();
        verifyNoInteractions(banRepository);
    }

    @Test
    @DisplayName("checkDeviceBan: device banned → BusinessException AUTH_012")
    void checkDeviceBan_banned_throws() {
        when(banRepository.findActiveBanByDeviceFingerprint(eq("fp-abc"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeBan("Known bad device")));

        assertThatThrownBy(() -> banService.checkDeviceBan("fp-abc"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_DEVICE_BANNED);
    }

    // ── recordFailedLoginAttempt ──────────────────────────────────────────────

    @Test
    @DisplayName("recordFailedLoginAttempt: below threshold → no ban created")
    void recordFailedLogin_belowThreshold_noBan() {
        // Config returns defaults (maxAttempts=5)
        when(banConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        FailedLoginAttempt attempt = FailedLoginAttempt.builder()
                .identifier("alice")
                .ipAddress("1.2.3.4")
                .attemptCount(3)
                .firstAttemptAt(LocalDateTime.now())
                .lastAttemptAt(LocalDateTime.now())
                .isBanned(false)
                .build();
        when(failedLoginAttemptRepository.findByIdentifierAndIpAddress("alice", "1.2.3.4"))
                .thenReturn(Optional.of(attempt));
        when(failedLoginAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        banService.recordFailedLoginAttempt("alice", "1.2.3.4", null);

        verify(banRepository, never()).save(any(Ban.class));
    }

    @Test
    @DisplayName("recordFailedLoginAttempt: reaches threshold → auto-bans IP")
    void recordFailedLogin_reachesThreshold_createsBan() {
        when(banConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        FailedLoginAttempt attempt = FailedLoginAttempt.builder()
                .identifier("alice")
                .ipAddress("1.2.3.4")
                .attemptCount(4) // 1 more will push to 5 = threshold
                .firstAttemptAt(LocalDateTime.now())
                .lastAttemptAt(LocalDateTime.now())
                .isBanned(false)
                .build();
        when(failedLoginAttemptRepository.findByIdentifierAndIpAddress("alice", "1.2.3.4"))
                .thenReturn(Optional.of(attempt));
        when(failedLoginAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Ban savedBan = activeBan("Auto-ban");
        savedBan.setId(99L);
        when(banRepository.save(any(Ban.class))).thenReturn(savedBan);

        banService.recordFailedLoginAttempt("alice", "1.2.3.4", null);

        verify(banRepository).save(any(Ban.class));
    }

    // ── clearFailedLoginAttempts ──────────────────────────────────────────────

    @Test
    @DisplayName("clearFailedLoginAttempts: unbanned attempt → deletes record")
    void clearFailedLogin_notBanned_deletesRecord() {
        FailedLoginAttempt attempt = FailedLoginAttempt.builder()
                .identifier("alice").ipAddress("1.2.3.4").isBanned(false).build();
        when(failedLoginAttemptRepository.findByIdentifierAndIpAddress("alice", "1.2.3.4"))
                .thenReturn(Optional.of(attempt));

        banService.clearFailedLoginAttempts("alice", "1.2.3.4");

        verify(failedLoginAttemptRepository).delete(attempt);
    }

    @Test
    @DisplayName("clearFailedLoginAttempts: banned attempt → does NOT delete")
    void clearFailedLogin_banned_doesNotDelete() {
        FailedLoginAttempt attempt = FailedLoginAttempt.builder()
                .identifier("alice").ipAddress("1.2.3.4").isBanned(true).build();
        when(failedLoginAttemptRepository.findByIdentifierAndIpAddress("alice", "1.2.3.4"))
                .thenReturn(Optional.of(attempt));

        banService.clearFailedLoginAttempts("alice", "1.2.3.4");

        verify(failedLoginAttemptRepository, never()).delete(any());
    }
}
