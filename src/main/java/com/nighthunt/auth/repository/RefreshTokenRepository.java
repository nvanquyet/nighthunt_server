package com.nighthunt.auth.repository;

import com.nighthunt.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    /** Delete ALL tokens for a user (used on login / logout / password-change).
     *  DELETE is used instead of UPDATE revoked=true to avoid InnoDB gap-lock
     *  deadlocks when 500+ concurrent logins for the same user_id race to
     *  UPDATE + INSERT in the same transaction. */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.userId = :userId")
    int revokeAllByUserId(@Param("userId") Long userId);
}
