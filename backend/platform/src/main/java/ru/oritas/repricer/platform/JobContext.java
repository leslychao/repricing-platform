package ru.oritas.repricer.platform;

import java.time.Instant;
import java.util.UUID;

public record JobContext(
    UUID id, Scope scope, String payload, long fence, Instant deadline, int attempt, String lane) {}
