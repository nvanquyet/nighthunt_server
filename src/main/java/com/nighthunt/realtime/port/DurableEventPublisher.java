package com.nighthunt.realtime.port;

public interface DurableEventPublisher {
    boolean isEnabled();
    void publish(String subject, String payload) throws Exception;
}
