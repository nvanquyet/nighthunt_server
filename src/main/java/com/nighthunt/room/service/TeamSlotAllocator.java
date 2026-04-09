package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.room.repository.RoomPlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
     *
     * @return int[2] where [0]=team, [1]=slot
     */
    public int[] allocate(Long roomId) {
        int team1Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_1);
        int team2Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_2);

        if (team1Count <= team2Count) {
            return new int[]{GameConstants.TEAM_1, team1Count};
        } else {
            return new int[]{GameConstants.TEAM_2, team2Count};
        }
    }
}
