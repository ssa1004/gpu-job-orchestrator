package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock UserQuotaRepository quotaRepository;
    @Mock JobRepository jobRepository;
    @Mock QuotaLock quotaLock;

    QuotaService service;

    @BeforeEach
    void setUp() {
        var props = new GwpProperties(
                new GwpProperties.Kubernetes(false, "ns", 86400, "http://cb"),
                new GwpProperties.Storage(false, 3600),
                new GwpProperties.Callback("secret"),
                new GwpProperties.Security(new GwpProperties.Security.Jwt(false)),
                new GwpProperties.Outbox(new GwpProperties.Outbox.Relay(false, 1000, 100, 5000, "gwp.", 10)),
                new GwpProperties.Quota(10, 16, false)
        );
        service = new QuotaService(quotaRepository, jobRepository, quotaLock, CLOCK, props);
    }

    @Test
    void enforce_acquiresOwnerAdvisoryLock_beforeReadingQuota() {
        when(quotaRepository.findByOwner("alice")).thenReturn(Optional.empty());
        when(jobRepository.sumActiveUsage(anyString(), any())).thenReturn(new OwnerActiveUsage(0, 0));

        service.enforceForSubmission("alice", 1);

        verify(quotaLock).acquireForOwner("alice");
    }

    @Test
    void enforce_passes_whenWellUnderQuota() {
        when(quotaRepository.findByOwner("alice")).thenReturn(Optional.empty());   // default quota 사용
        when(jobRepository.sumActiveUsage(anyString(), any())).thenReturn(new OwnerActiveUsage(2, 4));

        assertThatCode(() -> service.enforceForSubmission("alice", 2)).doesNotThrowAnyException();
    }

    @Test
    void enforce_throws_whenJobCountWouldExceedDefault() {
        when(quotaRepository.findByOwner("alice")).thenReturn(Optional.empty());
        when(jobRepository.sumActiveUsage(anyString(), any())).thenReturn(new OwnerActiveUsage(10, 0));

        assertThatThrownBy(() -> service.enforceForSubmission("alice", 1))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("active_jobs=10/10");
    }

    @Test
    void enforce_throws_whenGpuSumWouldExceed() {
        when(quotaRepository.findByOwner("alice")).thenReturn(Optional.empty());
        when(jobRepository.sumActiveUsage(anyString(), any())).thenReturn(new OwnerActiveUsage(2, 14));

        assertThatThrownBy(() -> service.enforceForSubmission("alice", 4))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("active_gpus=14/16");
    }

    @Test
    void enforce_usesUserSpecificQuota_whenPresent() {
        UserQuota custom = UserQuota.builder()
                .owner("alice").maxConcurrentJobs(2).maxGpuCount(2).updatedAt(CLOCK.instant()).build();
        when(quotaRepository.findByOwner("alice")).thenReturn(Optional.of(custom));
        when(jobRepository.sumActiveUsage(anyString(), any())).thenReturn(new OwnerActiveUsage(2, 0));

        assertThatThrownBy(() -> service.enforceForSubmission("alice", 1))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("active_jobs=2/2");
    }
}
