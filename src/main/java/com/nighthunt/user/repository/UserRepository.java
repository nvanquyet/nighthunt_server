package com.nighthunt.user.repository;

import com.nighthunt.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    // Admin queries
    List<User> findTop10ByOrderByEloDesc();

    @Query("""
        SELECT u FROM User u
        WHERE :search = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :search, '%'))
        """)
    Page<User> searchUsers(@Param("search") String search, Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :after")
    long countCreatedAfter(@Param("after") java.time.LocalDateTime after);
}

