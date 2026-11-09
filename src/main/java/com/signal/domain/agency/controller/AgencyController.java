package com.signal.domain.agency.controller;

import com.signal.domain.agency.dto.request.CreateConnectionRequest;
import com.signal.domain.agency.dto.response.AgencyListResponse;
import com.signal.domain.agency.dto.response.ConnectionResponse;
import com.signal.domain.agency.entity.Agency;
import com.signal.domain.agency.entity.AgencyConnection;
import com.signal.domain.agency.entity.AgencySituationType;
import com.signal.domain.agency.service.AgencyService;
import com.signal.global.i18n.LanguageContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agencies")
@RequiredArgsConstructor
public class AgencyController {

    private final AgencyService agencyService;

    @GetMapping
    public ResponseEntity<AgencyListResponse> getAgencies(
            @RequestParam(required = false) AgencySituationType situationType) {
        List<Agency> agencies = agencyService.getAgencies(situationType);
        return ResponseEntity.ok(AgencyListResponse.from(agencies, LanguageContext.current()));
    }

    @PostMapping("/{agencyId}/connections")
    public ResponseEntity<ConnectionResponse> createConnection(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long agencyId,
            @RequestBody CreateConnectionRequest request) {
        AgencyConnection connection = agencyService.createConnection(
                userId, agencyId, request.reportId(), request.connectionType());
        return ResponseEntity.status(HttpStatus.CREATED).body(ConnectionResponse.from(connection));
    }
}
