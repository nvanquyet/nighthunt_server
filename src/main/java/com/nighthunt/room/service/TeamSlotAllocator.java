package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Responsible for finding the next available team + slot for a room.
 * Balances teams evenly by assigning to the team with fewer players.
 */
@Component
@RequiredArgsConstructor
public class TeamSlotAllocator {

    private final RoomPlayerRepository roomPlayerRepository;

    /**
     * Allocate the next available team and slot for a new player.
     * Finds the first unused slot index on the team with fewer players.
     *
     * @return int[2] where [0]=team, [1]=slot
     */
    public int[] allocate(Long roomId) {
        int team1Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_1);
        int team2Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_2);

        int chosenTeam = (team1Count <= team2Count) ? GameConstants.TEAM_1 : GameConstants.TEAM_2;

        // Find occupied slots on the chosen team to avoid collision
        Set<Integer> occupiedSlots = roomPlayerRepository.findByRoomIdAndTeam(roomId, chosenTeam)
                .stream()
                .map(RoomPlayer::getSlot)
                .collect(Collectors.toSet());

        // Find the first available slot index
        int slot = 0;
        while (occupiedSlots.contains(slot)) {
            slot++;
        }

        return new int[]{chosenTeam, slot};
    }
}
