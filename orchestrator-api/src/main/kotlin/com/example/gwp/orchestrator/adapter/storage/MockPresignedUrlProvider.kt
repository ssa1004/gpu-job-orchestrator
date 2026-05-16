package com.example.gwp.orchestrator.adapter.storage

class MockPresignedUrlProvider : PresignedUrlProvider {

    override fun presignedGet(s3Uri: String): String =
        "https://mock-storage.local/presigned?uri=$s3Uri&exp=3600"
}
