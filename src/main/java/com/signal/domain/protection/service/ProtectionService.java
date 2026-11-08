package com.signal.domain.protection.service;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.entity.ProtectionStatus;
import com.signal.domain.protection.protector.ImageProtector;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.service.RiskAssessmentService;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import com.signal.global.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProtectionService {

    private final ProtectionRepository protectionRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final ImageProtector imageProtector;
    private final FileStorage fileStorage;

    @Transactional
    public Protection createProtection(Long userId, Long assessmentId, ProtectionLevel protectionLevel) {
        RiskAssessment riskAssessment = riskAssessmentService.getAssessment(userId, null, assessmentId);
        if (riskAssessment.getStatus() != RiskAssessmentStatus.COMPLETED) {
            throw new SignalException(ErrorCode.CONFLICT);
        }
        ProtectionLevel level = protectionLevel != null ? protectionLevel : ProtectionLevel.NORMAL;

        Protection protection = Protection.builder()
                .userId(userId)
                .assessmentId(assessmentId)
                .protectionLevel(level)
                .originalImageUrl(riskAssessment.getImageUrl())
                .build();
        Protection saved = protectionRepository.save(protection);

        TransactionUtils.runAfterCommit(() -> imageProtector.protect(saved.getId(), saved.getOriginalImageUrl(), level));

        return saved;
    }

    public Protection getProtection(Long userId, Long protectionId) {
        Protection protection = protectionRepository.findById(protectionId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        if (!protection.isOwnedBy(userId)) {
            throw new SignalException(ErrorCode.FORBIDDEN);
        }

        return protection;
    }

    public byte[] downloadProtectedImage(Long userId, Long protectionId) {
        Protection protection = getProtection(userId, protectionId);

        if (protection.getStatus() != ProtectionStatus.COMPLETED) {
            throw new SignalException(ErrorCode.PROTECTION_NOT_READY);
        }

        return fileStorage.load(protection.getProtectedImageUrl());
    }
}
