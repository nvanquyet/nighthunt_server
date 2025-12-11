package com.nighthunt.match.service;

import com.nighthunt.match.entity.HeadlessServer;
import com.nighthunt.match.repository.HeadlessServerRepository;
import com.nighthunt.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Headless auto-scaling removed (headless flow disabled)
@Slf4j
@Service
public class HeadlessServerAutoScalingService {
    // Intentionally empty
}

