package com.signal.domain.agency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.agency.entity.Agency;
import com.signal.domain.agency.entity.AgencyConnection;
import com.signal.domain.agency.entity.AgencySituationType;
import com.signal.domain.agency.entity.ConnectionType;
import com.signal.domain.agency.repository.AgencyConnectionRepository;
import com.signal.domain.agency.repository.AgencyRepository;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.service.ReportService;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyServiceTest {

    @Mock
    private AgencyRepository agencyRepository;

    @Mock
    private AgencyConnectionRepository agencyConnectionRepository;

    @Mock
    private ReportService reportService;

    private AgencyService agencyService;

    private Agency sampleAgency() {
        return Agency.builder()
                .name("디지털성범죄피해자지원센터")
                .phone("02-735-8994")
                .website("https://d4u.stop.or.kr")
                .availableHours("평일 09:00~18:00")
                .supportedActions(List.of("삭제 지원"))
                .supportedSituationTypes(List.of(AgencySituationType.DEEPFAKE_IMAGE, AgencySituationType.IMAGE_ABUSE))
                .build();
    }

    @BeforeEach
    void setUp() {
        agencyService = new AgencyService(agencyRepository, agencyConnectionRepository, reportService);
    }

    @Test
    void situationType이_없으면_전체_기관목록을_반환한다() {
        when(agencyRepository.findAll()).thenReturn(List.of(sampleAgency()));

        List<Agency> agencies = agencyService.getAgencies(null);

        assertThat(agencies).hasSize(1);
        verify(agencyRepository, never()).findBySituationType(any());
    }

    @Test
    void situationType이_있으면_해당_유형을_지원하는_기관만_반환한다() {
        when(agencyRepository.findBySituationType(AgencySituationType.CRISIS)).thenReturn(List.of());

        List<Agency> agencies = agencyService.getAgencies(AgencySituationType.CRISIS);

        assertThat(agencies).isEmpty();
        verify(agencyRepository).findBySituationType(AgencySituationType.CRISIS);
    }

    @Test
    void 연결을_생성하면_기관정보가_스냅샷으로_저장된다() {
        Agency agency = sampleAgency();
        when(agencyRepository.findById(1L)).thenReturn(Optional.of(agency));
        when(agencyConnectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyConnection connection = agencyService.createConnection(1L, 1L, null, ConnectionType.PHONE);

        assertThat(connection.getAgencyName()).isEqualTo("디지털성범죄피해자지원센터");
        assertThat(connection.getPhone()).isEqualTo("02-735-8994");
        assertThat(connection.getAttachedReportUrl()).isNull();
        verify(reportService, never()).getReport(any(), any());
    }

    @Test
    void reportId가_있으면_소유자검증_후_문서_경로를_첨부한다() {
        Agency agency = sampleAgency();
        when(agencyRepository.findById(1L)).thenReturn(Optional.of(agency));
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportService.getReport(1L, 5L)).thenReturn(report);
        when(agencyConnectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyConnection connection = agencyService.createConnection(1L, 1L, 5L, ConnectionType.DOCUMENT_SUBMIT);

        assertThat(connection.getAttachedReportUrl()).isEqualTo("/api/v1/reports/5/document");
    }

    @Test
    void 존재하지_않는_기관이면_예외가_발생한다() {
        when(agencyRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agencyService.createConnection(1L, 1L, null, ConnectionType.WEB))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void connectionType이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> agencyService.createConnection(1L, 1L, null, null))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    void 첨부한_신고서가_본인_것이_아니면_예외가_전파된다() {
        Agency agency = sampleAgency();
        when(agencyRepository.findById(1L)).thenReturn(Optional.of(agency));
        when(reportService.getReport(1L, 5L)).thenThrow(new SignalException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> agencyService.createConnection(1L, 1L, 5L, ConnectionType.WEB))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
