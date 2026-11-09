package com.signal.domain.agency.dto.request;

import com.signal.domain.agency.entity.ConnectionType;

public record CreateConnectionRequest(Long reportId, ConnectionType connectionType) {
}
