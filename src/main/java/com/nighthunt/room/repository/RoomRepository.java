package com.nighthunt.room.repository;

import com.nighthunt.room.entity.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
    Optional<Room> findByRoomCode(String roomCode);
    Optional<Room> findByMatchId(String matchId);
    List<Room> findByStatusAndIsPublicAndIsLocked(String status, Boolean isPublic, Boolean isLocked);
    List<Room> findByOwnerId(Long ownerId);

    @Query("""
        SELECT r FROM Room r
        WHERE r.status = :status
          AND r.isPublic = true
          AND (:mode IS NULL OR r.mode = :mode)
          AND (:mapId IS NULL OR r.mapId = :mapId)
        """)
    List<Room> findPublicWaitingRooms(
            @Param("status") String status,
            @Param("mode") String mode,
            @Param("mapId") String mapId);

    @Query("""
        SELECT r FROM Room r
        WHERE r.status = :status
          AND r.isPublic = true
          AND r.isLocked = false
          AND (r.password IS NULL OR r.password = '')
        """)
    List<Room> findQuickJoinRooms(@Param("status") String status);

    List<Room> findByStatus(String status);

    /** Admin: paginated, optionally filtered by status. Sorted newest first. */
    @Query("""
        SELECT r FROM Room r
        WHERE (:status IS NULL OR r.status = :status)
        ORDER BY r.createdAt DESC
        """)
    Page<Room> findFiltered(@Param("status") String status, Pageable pageable);

    /** Count rooms grouped by status — returns [status, count] pairs. */
    @Query("SELECT r.status, COUNT(r) FROM Room r GROUP BY r.status")
    List<Object[]> countGroupedByStatus();
}

