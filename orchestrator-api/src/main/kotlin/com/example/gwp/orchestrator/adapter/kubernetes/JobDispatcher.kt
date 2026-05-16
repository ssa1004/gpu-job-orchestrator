package com.example.gwp.orchestrator.adapter.kubernetes

import com.example.gwp.orchestrator.domain.Job

interface JobDispatcher {

    fun dispatch(job: Job): String

    fun cancel(k8sJobName: String)
}
