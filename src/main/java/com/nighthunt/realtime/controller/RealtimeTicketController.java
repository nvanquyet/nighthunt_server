package com.nighthunt.realtime.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.realtime.dto.RealtimeTicketResponse;
import com.nighthunt.realtime.service.RealtimeTicketService;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime")
@RequiredArgsConstructor
public class RealtimeTicketController {
    private final RealtimeTicketService realtimeTicketService;

    @PostMapping("/tickets")
    public ApiResponse<RealtimeTicketResponse> issueTicket() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", ErrorCodes.AUTH_REQUIRED);
        }
        return ApiResponse.ok(realtimeTicketService.issue(userId));
    }
}
