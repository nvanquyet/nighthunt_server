package com.nighthunt.map.repository;

import com.nighthunt.map.entity.GameMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameMapRepository extends JpaRepository<GameMap, Long> {

    Optional<GameMap> findByMapId(String mapId);

    boolean existsByMapId(String mapId);

    /** All active (non-deleted) maps ordered for UI display. */
    List<GameMap> findByIsActiveTrueOrderByDisplayOrderAsc();

    /** Active, non-locked (playable) maps. */
    List<GameMap> findByIsActiveTrueAndIsLockedFalseOrderByDisplayOrderAsc();
}
