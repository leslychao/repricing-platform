package ru.oritas.repricer.platform;

import java.util.UUID;

public record OperationResult(UUID operationId, String status) {}
