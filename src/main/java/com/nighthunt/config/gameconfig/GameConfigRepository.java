package com.nighthunt.config.gameconfig;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameConfigRepository extends JpaRepository<GameConfig, String> {
}
