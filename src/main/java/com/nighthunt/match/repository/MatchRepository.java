package com.nighthunt.match.repository;

import com.nighthunt.match.entity.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    Optional<Match> findByMatchId(String matchId);
    Optional<Match> findByRoomId(Long roomId);
    List<Match> findByStatus(String status);
    // findByHeadlessServerId removed - headless server functionality disabled
}

