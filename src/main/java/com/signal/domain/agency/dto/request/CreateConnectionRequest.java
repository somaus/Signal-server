package com.signal.domain.agency.dto.request;

import com.signal.domain.agency.entity.ConnectionType;
import jakarta.validation.constraints.NotNull;

public record CreateConnectionRequest(Long reportId, @NotNull ConnectionType connectionType) {
}
