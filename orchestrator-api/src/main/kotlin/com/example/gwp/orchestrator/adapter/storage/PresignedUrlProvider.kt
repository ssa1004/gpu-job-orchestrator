package com.example.gwp.orchestrator.adapter.storage

interface PresignedUrlProvider {

    fun presignedGet(s3Uri: String): String
}
