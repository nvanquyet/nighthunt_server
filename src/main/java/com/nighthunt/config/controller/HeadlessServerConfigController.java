package com.nighthunt.config.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.config.entity.HeadlessServerConfig;
import com.nighthunt.config.repository.HeadlessServerConfigRepository;
import com.nighthunt.config.service.HeadlessServerConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Headless server config endpoints removed (headless flow disabled)
@RestController
@RequestMapping("/api/config/headless-server")
public class HeadlessServerConfigController {
    // Intentionally left empty to avoid exposing headless config APIs
}

