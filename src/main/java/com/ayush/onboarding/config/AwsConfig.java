package com.ayush.onboarding.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableScheduling
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Value("${aws.endpoint-override:#{null}}")
    private String endpointOverride;

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointOverride != null) {
            builder.endpointOverride(URI.create(endpointOverride))
                   .forcePathStyle(true);  // Required for LocalStack
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointOverride != null) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }
}
