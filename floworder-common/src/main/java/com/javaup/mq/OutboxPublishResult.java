package com.javaup.mq;

/**
 * Publisher Confirm 与 Return 合并后的单一发布结果。
 */
public record OutboxPublishResult(
        Outcome outcome,
        String error,
        long confirmLatencyMillis
) {

    public enum Outcome {
        ACK_ROUTED,
        RETURNED,
        NACK,
        TIMEOUT,
        EXCEPTION
    }

    public boolean successful() {
        return outcome == Outcome.ACK_ROUTED;
    }

    public static OutboxPublishResult ack(long latencyMillis) {
        return new OutboxPublishResult(Outcome.ACK_ROUTED, null, latencyMillis);
    }

    public static OutboxPublishResult failed(Outcome outcome, String error, long latencyMillis) {
        return new OutboxPublishResult(outcome, error, latencyMillis);
    }
}
