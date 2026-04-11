package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class TimeSeriesDTO {
    private List<String>  labels;
    private List<Integer> onlineUsers;
    private List<Integer> queueDepth;
    private List<Integer> inGameRooms;
    private List<Integer> activeDsServers;
}
