package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.domain.Job;

public interface JobDispatcher {

    String dispatch(Job job);

    void cancel(String k8sJobName);
}
