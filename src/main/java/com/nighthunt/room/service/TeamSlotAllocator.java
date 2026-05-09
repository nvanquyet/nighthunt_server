package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        return allocate(roomId, Integer.MAX_VALUE);
    }

    /**
     * Allocate the next available team and slot, bounded by the room mode.
     */
    public int[] allocate(Long roomId, int slotsPerTeam) {
        int maxSlots = Math.max(1, slotsPerTeam);
        int team1Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_1);
        int team2Count = roomPlayerRepository.countByRoomIdAndTeam(roomId, GameConstants.TEAM_2);

        int chosenTeam = (team1Count <= team2Count) ? GameConstants.TEAM_1 : GameConstants.TEAM_2;
        int alternateTeam = chosenTeam == GameConstants.TEAM_1 ? GameConstants.TEAM_2 : GameConstants.TEAM_1;

        int slot = findFreeSlot(roomId, chosenTeam, maxSlots);
        if (slot >= 0) {
            return new int[]{chosenTeam, slot};
        }

        slot = findFreeSlot(roomId, alternateTeam, maxSlots);
        if (slot >= 0) {
            return new int[]{alternateTeam, slot};
        }

        throw new IllegalStateException("No free room slots available for room " + roomId);
    }

    private int findFreeSlot(Long roomId, int team, int maxSlots) {
        Set<Integer> occupiedSlots = roomPlayerRepository.findByRoomIdAndTeam(roomId, team)
                .stream()
                .map(RoomPlayer::getSlot)
                .collect(Collectors.toSet());

        for (int slot = 0; slot < maxSlots; slot++) {
            if (!occupiedSlots.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }
}
