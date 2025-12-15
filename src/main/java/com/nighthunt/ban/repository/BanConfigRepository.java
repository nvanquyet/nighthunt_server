package com.nighthunt.ban.repository;

import com.nighthunt.ban.entity.BanConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BanConfigRepository extends JpaRepository<BanConfig, Long> {
    Optional<BanConfig> findByConfigKey(String configKey);
}

