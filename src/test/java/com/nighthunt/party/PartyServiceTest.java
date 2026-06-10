package com.nighthunt.party;

import com.nighthunt.friend.repository.BlockedUserRepository;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.dto.PartyDTO;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyInvitation;
import com.nighthunt.party.repository.PartyInvitationRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.party.service.PartyService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyServiceTest {

    @Mock private PartyRepository partyRepository;
    @Mock private PartyMemberRepository partyMemberRepository;
    @Mock private PartyInvitationRepository partyInvitationRepository;
    @Mock private UserRepository userRepository;
    @Mock private BlockedUserRepository blockedUserRepository;
    @Mock private PlayerStatusService playerStatusService;
    @Mock private GameModeService gameModeService;
    @Mock private MessageBrokerService messageBrokerService;

    private PartyService service;

    @BeforeEach
    void setUp() {
        service = new PartyService(
                partyRepository,
                partyMemberRepository,
                partyInvitationRepository,
                userRepository,
                blockedUserRepository,
                playerStatusService,
                gameModeService,
                messageBrokerService);
    }

    @Test
    void createPartyUsesLargestAvailableTeamSize() {
        User host = User.builder().id(1L).username("host").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(host));
        when(partyMemberRepository.existsByUserId(1L)).thenReturn(false);
        when(gameModeService.getAvailableGameModes()).thenReturn(List.of(
                mode("1v1", 1),
                mode("5v5", 5)));
        when(partyRepository.save(any(Party.class))).thenAnswer(invocation -> {
            Party party = invocation.getArgument(0);
            party.setId(10L);
            return party;
        });
        when(partyMemberRepository.findByPartyIdOrderByJoinOrderAsc(10L)).thenReturn(List.of());

        PartyDTO result = service.createParty(1L);

        ArgumentCaptor<Party> partyCaptor = ArgumentCaptor.forClass(Party.class);
        verify(partyRepository).save(partyCaptor.capture());
        assertThat(partyCaptor.getValue().getMaxMembers()).isEqualTo(5);
        assertThat(result.getMaxMembers()).isEqualTo(5);
    }

    @Test
    void getPendingInvitationsExpiresStaleRows() {
        PartyInvitation invitation = PartyInvitation.builder()
                .id(100L)
                .partyId(null)
                .inviterUserId(1L)
                .inviteeUserId(2L)
                .invitationStatus("PENDING")
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(partyInvitationRepository.findByInviteeUserIdAndInvitationStatus(2L, "PENDING"))
                .thenReturn(List.of(invitation));

        assertThat(service.getPendingInvitations(2L)).isEmpty();
        assertThat(invitation.getInvitationStatus()).isEqualTo("EXPIRED");
        verify(partyInvitationRepository).save(invitation);
        verify(messageBrokerService).publishPartyInvitationExpired(null, 1L, 2L, 100L);
    }

    private GameModeDTO mode(String key, int playersPerTeam) {
        return GameModeDTO.builder()
                .modeKey(key)
                .modeStatus("AVAILABLE")
                .isActive(true)
                .playersPerTeam(playersPerTeam)
                .build();
    }
}
