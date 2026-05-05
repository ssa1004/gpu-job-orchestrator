package com.example.gwp.orchestrator.adapter.storage;

public interface PresignedUrlProvider {

    String presignedGet(String s3Uri);
}
