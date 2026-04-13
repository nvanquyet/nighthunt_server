package com.nighthunt.room.repository;

import com.nighthunt.room.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
    Optional<Room> findByRoomCode(String roomCode);Optional<Room> findByMatchId(String matchId);    List<Room> findByStatusAndIsPublicAndIsLocked(String status, Boolean isPublic, Boolean isLocked);
    List<Room> findByOwnerId(Long ownerId);
    
    @Query("SELECT r FROM Room r WHERE r.status = :status AND r.isPublic = true AND r.isLocked = false")
    List<Room> findAvailablePublicRooms(@Param("status") String status);
    
    List<Room> findByStatus(String status);
}

