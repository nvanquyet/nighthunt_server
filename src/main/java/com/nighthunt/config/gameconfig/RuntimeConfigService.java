package com.nighthunt.config.gameconfig;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runtime configuration service — reads and writes the {@code game_config} table.
 * All game parameters that the admin should be able to tune at runtime
 * (ELO ranges, match timing, DS port range, etc.) live here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final GameConfigRepository repo;

    /** Return all config entries for admin display. */
    @Transactional(readOnly = true)
    public List<GameConfig> getAll() {
        return repo.findAll();
    }

    /** Read a single value; throws if key not found. */
    @Transactional(readOnly = true)
    public GameConfig getOrThrow(String key) {
        return repo.findById(key).orElseThrow(() ->
                new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Config key not found: " + key));
    }

    /** Read a value as String, returning fallback if key is missing. */
    @Transactional(readOnly = true)
    public String getString(String key, String fallback) {
        return repo.findById(key).map(GameConfig::getConfigValue).orElse(fallback);
    }

    /** Read a value as int, returning fallback if key is missing or unparseable. */
    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Update a config value at runtime.
     * Only the value field is changed — key, type and description remain unchanged.
     */
    @Transactional
    public GameConfig setValue(String key, String newValue) {
        GameConfig cfg = repo.findById(key).orElseThrow(() ->
                new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Config key not found: " + key));
        String oldValue = cfg.getConfigValue();
        cfg.setConfigValue(newValue);
        GameConfig saved = repo.save(cfg);
        log.info("[RuntimeConfig] {} = {} (was: {})", key, newValue, oldValue);
        return saved;
    }
}
