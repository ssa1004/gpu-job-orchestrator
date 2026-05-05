package com.example.gwp.orchestrator.domain;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobAccessControlTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobQueryService queryService;
    @Mock JobLifecycleService lifecycleService;

    JobAccessControl access;

    @BeforeEach
    void setUp() {
        access = new JobAccessControl(queryService, lifecycleService);
    }

    private Job ownedBy(String owner) {
        return Job.submit(new JobSpec(owner, "s3://b/i", "eng:1", 1), null, CLOCK);
    }

    @Test
    void getOwned_returnsForOwner() {
        Job job = ownedBy("alice");
        UUID id = job.getId();
        when(queryService.get(id)).thenReturn(job);

        Job result = access.getOwned(id, "alice", false);

        assertThat(result).isSameAs(job);
    }

    @Test
    void getOwned_throwsForOtherOwner() {
        Job job = ownedBy("alice");
        when(queryService.get(job.getId())).thenReturn(job);

        assertThatThrownBy(() -> access.getOwned(job.getId(), "bob", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getOwned_returnsForAdminEvenIfNotOwner() {
        Job job = ownedBy("alice");
        when(queryService.get(job.getId())).thenReturn(job);

        Job result = access.getOwned(job.getId(), "bob", true);

        assertThat(result).isSameAs(job);
    }

    @Test
    void cancelOwned_delegatesToLifecycleService() {
        Job job = ownedBy("alice");
        when(queryService.get(job.getId())).thenReturn(job);
        when(lifecycleService.cancel(job.getId())).thenReturn(job);

        access.cancelOwned(job.getId(), "alice", false);

        verify(lifecycleService).cancel(job.getId());
    }

    @Test
    void cancelOwned_throwsForOtherOwner() {
        Job job = ownedBy("alice");
        when(queryService.get(job.getId())).thenReturn(job);

        assertThatThrownBy(() -> access.cancelOwned(job.getId(), "bob", false))
                .isInstanceOf(AccessDeniedException.class);
        verify(lifecycleService, never()).cancel(any());
    }
}
