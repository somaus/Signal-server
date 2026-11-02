package com.signal.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.monitoring.entity.Detection;
import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import com.signal.domain.monitoring.monitor.FaceMonitor;
import com.signal.domain.monitoring.repository.DetectionRepository;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    @Mock
    private MonitoringRepository monitoringRepository;

    @Mock
    private DetectionRepository detectionRepository;

    @Mock
    private FaceMonitor faceMonitor;

    @Mock
    private FileStorage fileStorage;

    private MonitoringService monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = new MonitoringService(monitoringRepository, detectionRepository, faceMonitor, fileStorage);
    }

    @Test
    void 모니터링을_생성하면_ACTIVE_상태로_저장되고_탐지가_시작된다() {
        MockMultipartFile image = new MockMultipartFile("referenceImage", "face.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorage.store(any(), anyString())).thenReturn("/uploads/monitorings/generated.png");
        when(monitoringRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Monitoring monitoring = monitoringService.createMonitoring(1L, image);

        assertThat(monitoring.getUserId()).isEqualTo(1L);
        assertThat(monitoring.getStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        assertThat(monitoring.getReferenceImageUrl()).isEqualTo("/uploads/monitorings/generated.png");
        verify(faceMonitor).startMonitoring(eq(monitoring.getId()), eq("/uploads/monitorings/generated.png"));
    }

    @Test
    void 지원하지_않는_파일_형식이면_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("referenceImage", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> monitoringService.createMonitoring(1L, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 파일_크기가_10MB를_초과하면_예외가_발생한다() {
        byte[] tooLarge = new byte[(int) (10L * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("referenceImage", "big.png", "image/png", tooLarge);

        assertThatThrownBy(() -> monitoringService.createMonitoring(1L, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 소유자가_조회하면_탐지목록을_페이지로_반환한다() {
        Monitoring monitoring = Monitoring.builder().userId(1L).referenceImageUrl("/uploads/monitorings/a.png").build();
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));
        Detection detection = Detection.builder()
                .monitoringId(1L).sourceUrl("https://a").thumbnailUrl("https://b").similarity(0.9).build();
        Pageable pageable = PageRequest.of(0, 20);
        Page<Detection> page = new PageImpl<>(java.util.List.of(detection), pageable, 1);
        when(detectionRepository.findByMonitoringIdOrderByDetectedAtDesc(1L, pageable)).thenReturn(page);

        Page<Detection> result = monitoringService.getDetections(1L, 1L, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).containsExactly(detection);
    }

    @Test
    void 존재하지_않는_모니터링을_조회하면_예외가_발생한다() {
        when(monitoringRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monitoringService.getDetections(1L, 1L, PageRequest.of(0, 20)))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_조회시_예외가_발생한다() {
        Monitoring monitoring = Monitoring.builder().userId(2L).referenceImageUrl("/uploads/monitorings/a.png").build();
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));

        assertThatThrownBy(() -> monitoringService.getDetections(1L, 1L, PageRequest.of(0, 20)))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void 소유자가_삭제하면_탐지목록도_함께_삭제된다() {
        Monitoring monitoring = Monitoring.builder().userId(1L).referenceImageUrl("/uploads/monitorings/a.png").build();
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));

        monitoringService.deleteMonitoring(1L, 1L);

        verify(detectionRepository).deleteAllByMonitoringId(1L);
        verify(monitoringRepository).delete(monitoring);
    }

    @Test
    void 삭제하면_기준_이미지_파일도_지운다() {
        Monitoring monitoring = Monitoring.builder().userId(1L).referenceImageUrl("/uploads/monitorings/a.png").build();
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));

        monitoringService.deleteMonitoring(1L, 1L);

        verify(fileStorage).delete("/uploads/monitorings/a.png");
    }

    @Test
    void 사용자당_모니터링_수를_넘으면_예외가_발생한다() {
        when(monitoringRepository.countByUserId(1L)).thenReturn(3L);
        MockMultipartFile file = new MockMultipartFile("referenceImage", "a.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> monitoringService.createMonitoring(1L, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MONITORING_LIMIT_EXCEEDED);
    }

    @Test
    void 존재하지_않는_모니터링을_삭제하면_예외가_발생한다() {
        when(monitoringRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monitoringService.deleteMonitoring(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_삭제시_예외가_발생한다() {
        Monitoring monitoring = Monitoring.builder().userId(2L).referenceImageUrl("/uploads/monitorings/a.png").build();
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));

        assertThatThrownBy(() -> monitoringService.deleteMonitoring(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
