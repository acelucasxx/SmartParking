package com.smartparking.dtubench;

import java.time.Instant;

/**
 * 一条上行载荷。
 *
 * @param nanos {@link System#nanoTime()}，算时延用
 */
public record Received(Instant at, long nanos, byte[] payload, PayloadAnalyzer.Analysis analysis) {}
