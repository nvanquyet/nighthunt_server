package com.nighthunt.match.model;

import com.nighthunt.match.dto.MatchPresenceState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Cached presence state for one player in one match.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchPresenceSnapshot implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String matchId;
    private Long roomId;
    private Long userId;
    private String displayName;
    private MatchPresenceState state;
    private String reason;
    private LocalDateTime reportedAt;
    private LocalDateTime disconnectedAt;
    private LocalDateTime abandonedAt;
    private boolean abandoned;
}
