package com.example.gwp.orchestrator.domain;

import com.example.gwp.orchestrator.adapter.storage.PresignedUrlProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobQueryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobRepository jobRepository;
    @Mock PresignedUrlProvider presignedUrlProvider;

    JobQueryService service;

    @BeforeEach
    void setUp() {
        service = new JobQueryService(jobRepository, presignedUrlProvider);
    }

    @Test
    void get_returnsJob_whenFound() {
        Job job = submittedJob();
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        Job result = service.get(job.getId());

        assertThat(result).isSameAs(job);
    }

    @Test
    void get_throws_whenMissing() {
        Job job = submittedJob();
        when(jobRepository.findById(job.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(job.getId()))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void list_filtersByOwnerAndStatus_whenStatusIsGiven() {
        Job job = submittedJob();
        var pageable = PageRequest.of(0, 20);
        when(jobRepository.findByOwnerAndStatus("alice", JobStatus.QUEUED, pageable))
                .thenReturn(new PageImpl<>(List.of(job), pageable, 1));

        var result = service.list("alice", JobStatus.QUEUED, pageable);

        assertThat(result.getContent()).containsExactly(job);
        verify(jobRepository).findByOwnerAndStatus("alice", JobStatus.QUEUED, pageable);
        verify(jobRepository, never()).findByOwner("alice", pageable);
    }

    @Test
    void resultUrl_returnsPresignedUrl_whenJobSucceeded() {
        Job job = submittedJob();
        job.markSucceeded("s3://bucket/out.bin", CLOCK);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(presignedUrlProvider.presignedGet("s3://bucket/out.bin"))
                .thenReturn("https://storage.local/presigned/out.bin");

        String result = service.resultUrl(job.getId());

        assertThat(result).isEqualTo("https://storage.local/presigned/out.bin");
    }

    @Test
    void resultUrl_throwsAndDoesNotPresign_whenResultIsUnavailable() {
        Job job = submittedJob();
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.resultUrl(job.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job result not available");
        verify(presignedUrlProvider, never()).presignedGet(anyString());
    }

    private Job submittedJob() {
        return Job.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1), null, CLOCK);
    }
}
