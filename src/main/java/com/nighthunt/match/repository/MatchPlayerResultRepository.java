package com.nighthunt.match.repository;

import com.nighthunt.match.entity.MatchPlayerResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchPlayerResultRepository extends JpaRepository<MatchPlayerResult, Long> {

    List<MatchPlayerResult> findByMatchId(String matchId);

    List<MatchPlayerResult> findByUserId(Long userId);
}
