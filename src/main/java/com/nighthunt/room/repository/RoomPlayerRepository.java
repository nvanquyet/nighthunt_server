package com.nighthunt.room.repository;

import com.nighthunt.room.entity.RoomPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomPlayerRepository extends JpaRepository<RoomPlayer, Long> {
    List<RoomPlayer> findByRoomId(Long roomId);
    Optional<RoomPlayer> findByRoomIdAndUserId(Long roomId, Long userId);
    List<RoomPlayer> findByRoomIdAndTeam(Long roomId, Integer team);
    List<RoomPlayer> findByUserId(Long userId); // Find all rooms a user is in
    void deleteByRoomId(Long roomId);
    void deleteByRoomIdAndUserId(Long roomId, Long userId);
    
    @Query("SELECT COUNT(rp) FROM RoomPlayer rp WHERE rp.roomId = :roomId")
    int countByRoomId(@Param("roomId") Long roomId);
    
    @Query("SELECT COUNT(rp) FROM RoomPlayer rp WHERE rp.roomId = :roomId AND rp.team = :team")
    int countByRoomIdAndTeam(@Param("roomId") Long roomId, @Param("team") Integer team);

    /**
     * Returns true if the user is currently inside an active room (WAITING or IN_GAME).
     * Used to enforce mutual exclusion: a player may not join the ranked queue while in a custom lobby.
     */
    @Query("""
            SELECT COUNT(rp) > 0 FROM RoomPlayer rp, Room r
            WHERE rp.roomId = r.id
              AND rp.userId  = :userId
              AND r.status   IN ('WAITING', 'IN_GAME')
            """)
    boolean existsUserInActiveRoom(@Param("userId") Long userId);
}

