package com.llmops.rag.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops the gRPC Netty server alongside the Spring context lifecycle.
 * <p>
 * Uses {@link SmartLifecycle} so it starts after all Spring beans are ready
 * and stops gracefully before the JVM exits.
 * </p>
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final ReasoningGrpcService reasoningGrpcService;
    private final int grpcPort;
    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(
            ReasoningGrpcService reasoningGrpcService,
            @Value("${grpc.server.port:9091}") int grpcPort) {
        this.reasoningGrpcService = reasoningGrpcService;
        this.grpcPort = grpcPort;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(grpcPort)
                    .addService(reasoningGrpcService)
                    .build()
                    .start();
            running = true;
            log.info("gRPC server started on port {}", grpcPort);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start gRPC server on port " + grpcPort, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("Shutting down gRPC server...");
            server.shutdown();
            try {
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            running = false;
            log.info("gRPC server shut down.");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start after all beans are ready (default phase 0), stop before them
        return Integer.MAX_VALUE;
    }
}
