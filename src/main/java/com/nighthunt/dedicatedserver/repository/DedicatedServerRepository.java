package com.nighthunt.dedicatedserver.repository;

import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DedicatedServerRepository extends JpaRepository<DedicatedServer, Long> {

    Optional<DedicatedServer> findByServerId(String serverId);

    /** Tìm server available (ready + có chỗ) theo region và mapId, ưu tiên gần đầy nhất */
    @Query("""
        SELECT d FROM DedicatedServer d
        WHERE d.region = :region
          AND d.status = 'ready'
          AND d.currentPlayers < d.maxPlayers
          AND (:mapId IS NULL OR d.mapId IS NULL OR d.mapId = :mapId)
        ORDER BY d.currentPlayers DESC
        LIMIT 1
    """)
    Optional<DedicatedServer> findAvailable(@Param("region") String region, @Param("mapId") String mapId);

    /** Tìm servers đang starting quá lâu (timeout → cleanup) */
    @Query("""
        SELECT d FROM DedicatedServer d
        WHERE d.status = 'starting'
          AND d.startedAt < :cutoff
    """)
    List<DedicatedServer> findStaleStarting(@Param("cutoff") LocalDateTime cutoff);

    /** Tìm servers không heartbeat quá lâu (crash → cleanup) */
    @Query("""
        SELECT d FROM DedicatedServer d
        WHERE d.status IN ('ready', 'in_game')
          AND (d.lastHeartbeatAt IS NULL OR d.lastHeartbeatAt < :cutoff)
    """)
    List<DedicatedServer> findDeadServers(@Param("cutoff") LocalDateTime cutoff);

    /** Kiểm tra port đang bị dùng chưa */
    boolean existsByPortAndStatusNot(Integer port, String status);

    /** Tìm DS đang phục vụ một match cụ thể (để reclaim khi match kết thúc). */
    @Query("""
        SELECT d FROM DedicatedServer d
        WHERE d.matchId = :matchId
          AND d.status IN ('ready', 'in_game', 'starting')
    """)
    Optional<DedicatedServer> findActiveByMatchId(@Param("matchId") String matchId);
}
