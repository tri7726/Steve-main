package com.steve.ai.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GrpcAiClient {
    private static final Logger LOGGER = LogManager.getLogger();

    // Thread-safe lazy singleton via double-checked locking
    private static volatile GrpcAiClient INSTANCE;
    
    public static GrpcAiClient getInstance() {
        if (INSTANCE == null) {
            synchronized (GrpcAiClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GrpcAiClient("127.0.0.1", 50051);
                }
            }
        }
        return INSTANCE;
    }

    private final String host;
    private final int port;
    private ManagedChannel channel;
    private SteveAiServiceGrpc.SteveAiServiceStub asyncStub;
    
    private StreamObserver<BotState> requestObserver;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);

    // Thread-safe queue: gRPC async thread nhét vào, Game tick thread kéo ra
    private final ConcurrentLinkedQueue<AiCommand> commandQueue = new ConcurrentLinkedQueue<>();

    // Reconnect scheduler (single background thread)
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "steve-grpc-reconnect");
                t.setDaemon(true);
                return t;
            });

    public GrpcAiClient(String host, int port) {
        this.host = host;
        this.port = port;
        buildChannel();
    }

    private void buildChannel() {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.asyncStub = SteveAiServiceGrpc.newStub(channel);
    }

    public void start() {
        if (isConnected.get()) return;
        doConnect();
    }

    private void doConnect() {
        LOGGER.info("Starting Steve AI gRPC stream to Node.js server ({}:{})...", host, port);
        
        StreamObserver<AiCommand> responseObserver = new StreamObserver<AiCommand>() {
            @Override
            public void onNext(AiCommand command) {
                LOGGER.info("Received AiCommand via gRPC: [{}] type={}",
                        command.getCommandId(), command.getCommandType());
                commandQueue.offer(command);
            }

            @Override
            public void onError(Throwable t) {
                LOGGER.error("gRPC Stream Error: {} — scheduling reconnect in 5s", t.getMessage());
                isConnected.set(false);
                scheduleReconnect(5);
            }

            @Override
            public void onCompleted() {
                LOGGER.info("gRPC Stream closed by server — scheduling reconnect in 3s");
                isConnected.set(false);
                scheduleReconnect(3);
            }
        };

        try {
            this.requestObserver = asyncStub.streamControl(responseObserver);
            isConnected.set(true);
            isReconnecting.set(false);
            LOGGER.info("gRPC bidirectional stream opened successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to open gRPC stream: {} — scheduling reconnect in 5s", e.getMessage());
            isConnected.set(false);
            scheduleReconnect(5);
        }
    }

    /** Schedule a reconnect attempt, avoiding duplicate schedulings. */
    private void scheduleReconnect(int delaySeconds) {
        if (isReconnecting.compareAndSet(false, true)) {
            reconnectScheduler.schedule(() -> {
                LOGGER.info("Attempting gRPC reconnect...");
                // Rebuild channel to clear any broken state
                try {
                    if (!channel.isShutdown()) {
                        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ignored) {}
                buildChannel();
                doConnect();
            }, delaySeconds, TimeUnit.SECONDS);
        }
    }

    public void sendBotState(long tick, double hp, double hunger, double x, double y, double z, 
                             String invJson, String nearbyEntitiesJson, String obsJson,
                             boolean lastActionSuccess, String lastTask, String lastError,
                             String executedSkill, double durationSeconds, byte[] screenshot,
                             String biome, String dimension, long worldTime, boolean isRaining) {
        if (!isConnected.get() || requestObserver == null) {
            return;
        }
        
        try {
            BotState.Builder builder = BotState.newBuilder()
                    .setTick(tick)
                    .setHealth(hp)
                    .setHunger(hunger)
                    .setX(x)
                    .setY(y)
                    .setZ(z)
                    .setInventoryJson(invJson)
                    .setNearbyEntitiesJson(nearbyEntitiesJson)
                    .setObservationsJson(obsJson)
                    .setLastActionSuccess(lastActionSuccess)
                    .setLastTask(lastTask)
                    .setLastError(lastError)
                    .setExecutedSkill(executedSkill)
                    .setDurationSeconds(durationSeconds)
                    .setBiome(biome != null ? biome : "unknown")
                    .setDimension(dimension != null ? dimension : "overworld")
                    .setWorldTime(worldTime)
                    .setIsRaining(isRaining);
            
            if (screenshot != null && screenshot.length > 0) {
                builder.setScreenshot(com.google.protobuf.ByteString.copyFrom(screenshot));
            }
                    
            requestObserver.onNext(builder.build());
        } catch (Exception e) {
            LOGGER.error("Failed to send BotState via gRPC: {}", e.getMessage());
            isConnected.set(false);
            scheduleReconnect(5);
        }
    }

    public void shutdown() {
        LOGGER.info("Shutting down gRPC client channel...");
        reconnectScheduler.shutdownNow();
        if (requestObserver != null) {
            try { requestObserver.onCompleted(); } catch (Exception ignored) {}
        }
        try {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            LOGGER.error("gRPC shutdown interrupted", e);
        }
    }
    
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Lấy lệnh tiếp theo từ hàng đợi (gọi từ Game Tick Thread).
     * Trả về null nếu không có lệnh mới.
     */
    public AiCommand pollCommand() {
        return commandQueue.poll();
    }

    public boolean hasCommands() {
        return !commandQueue.isEmpty();
    }
}
