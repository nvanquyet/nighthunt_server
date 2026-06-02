package com.nighthunt.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.map.entity.GameMap;
import com.nighthunt.map.repository.GameMapRepository;
import com.nighthunt.map.service.GameMapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameMapServiceTest {

    @Mock private GameMapRepository mapRepository;

    private GameMapService service;

    @BeforeEach
    void setUp() {
        service = new GameMapService(mapRepository, new ObjectMapper());
    }

    @Test
    void rankedMatchmakingAcceptsDeclaredModeAndPlayerCount() {
        when(mapRepository.findByMapId("map_01")).thenReturn(Optional.of(map(
                "[\"2v2\",\"4v4\"]",
                "[4,8]"
        )));

        assertThat(service.isMapValidForMatchmaking("map_01", "4v4", 8)).isTrue();
    }

    @Test
    void rankedMatchmakingRejectsUnsupportedMode() {
        when(mapRepository.findByMapId("map_01")).thenReturn(Optional.of(map(
                "[\"2v2\"]",
                "[4,8]"
        )));

        assertThat(service.isMapValidForMatchmaking("map_01", "4v4", 8)).isFalse();
    }

    @Test
    void rankedMatchmakingRejectsUnsupportedPlayerCount() {
        when(mapRepository.findByMapId("map_01")).thenReturn(Optional.of(map(
                "[\"2v2\",\"4v4\"]",
                "[4,6]"
        )));

        assertThat(service.isMapValidForMatchmaking("map_01", "4v4", 8)).isFalse();
    }

    private GameMap map(String supportedModes, String supportedPlayerCounts) {
        return GameMap.builder()
                .mapId("map_01")
                .displayName("Industrial Zone")
                .sceneName("02_Map_01")
                .supportedModesJson(supportedModes)
                .supportedPlayerCountsJson(supportedPlayerCounts)
                .isActive(true)
                .isLocked(false)
                .build();
    }
}
