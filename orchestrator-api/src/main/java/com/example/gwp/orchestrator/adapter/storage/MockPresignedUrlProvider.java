package com.example.gwp.orchestrator.adapter.storage;

public class MockPresignedUrlProvider implements PresignedUrlProvider {

    @Override
    public String presignedGet(String s3Uri) {
        return "https://mock-storage.local/presigned?uri=" + s3Uri + "&exp=3600";
    }
}
