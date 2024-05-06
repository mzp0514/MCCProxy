package org.mccproxy.service;


import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.mccproxy.proxy.ItemRecord;
import org.mccproxy.proxy.MCCProxy;
import org.mccproxy.utils.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * A sample gRPC server that serve the RouteGuide (see route_guide.proto) service.
 */
public class MCCProxyServer {
    private static final Logger logger =
            LoggerFactory.getLogger(MCCProxyServer.class.getName());

    private final int port;
    private final Server server;
    private final MCCProxyService mccProxyService;


    /**
     * Create a RouteGuide server listening on {@code port}.
     */
    public MCCProxyServer(int port) throws IOException {
        this(Grpc.newServerBuilderForPort(port,
                                          InsecureServerCredentials.create()),
             port);
    }

    public MCCProxyServer(ServerBuilder<?> serverBuilder, int port)
            throws IOException {
        this.port = port;
        this.mccProxyService = new MCCProxyService();
        server = serverBuilder.addService(this.mccProxyService).build();
    }

    /**
     * Main method.  This comment makes the linter happy.
     */
    public static void main(String[] args) throws Exception {
        MCCProxyServer server = new MCCProxyServer(8980);
        server.start();
        server.blockUntilShutdown();
    }

    /**
     * Start serving requests.
     */
    public void start() throws IOException {
        server.start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println(
                        "*** shutting down gRPC server since JVM is shutting down");
                try {
                    MCCProxyServer.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
            }
        });
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() throws InterruptedException {
        if (server != null) {
            this.mccProxyService.stopTaskProcessingThreads();
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Our implementation of RouteGuide service.
     *
     * <p>See route_guide.proto for details of the methods.
     */
    private static class MCCProxyService
            extends MCCProxyServiceGrpc.MCCProxyServiceImplBase {

        private final BlockingQueue<Runnable> taskQueue =
                new LinkedBlockingQueue<>();

        private final MCCProxy proxy;
        private volatile boolean isRunning = true;

        MCCProxyService() {
            this.proxy = new MCCProxy("src/main/resources/proxy-config.yaml");
            startTaskProcessingThread();
        }

        private void startTaskProcessingThread() {
            new Thread(() -> {
                while (isRunning) {
                    try {
                        Runnable task = taskQueue.take();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        public void stopTaskProcessingThreads() {
            isRunning = false;
        }

        public void addReadTask(Runnable task) {
            taskQueue.add(task);
        }

        public void addInvalidateTask(Runnable task) {
            taskQueue.add(task);
        }

        /**
         * @param request          the requested location for the feature.
         * @param responseObserver the observer that will receive the feature at the requested point.
         */
        @Override
        public void read(ReadRequest request,
                         StreamObserver<ReadResponse> responseObserver) {
            logger.info("Received read request for keys: {}",
                        request.getKeysList());
            addReadTask(() -> {
                List<ItemRecord> result =
                        this.proxy.processRead(request.getKeysList());

                ReadResponse.Builder responseBuilder =
                        ReadResponse.newBuilder();

                for (ItemRecord record : result) {
                    Item item = Item.newBuilder().setKey(record.getKey())
                            .setValue(record.getValue()).build();
                    responseBuilder.addItems(item);
                }

                ReadResponse response = responseBuilder.build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            });
        }

        /**
         * @param request          the bounding rectangle for the requested features.
         * @param responseObserver the observer that will receive the features.
         */
        @Override
        public void invalidate(InvalidateRequest request,
                               StreamObserver<InvalidateResponse> responseObserver) {
            logger.info(
                    "Received invalidate request for keys: {} timestamp: {}",
                    request.getKeysList(), request.getTimestamp());

            addInvalidateTask(() -> {
                boolean result =
                        this.proxy.processInvalidation(request.getKeysList(),
                                                       TimeUtils.convertProtoTimestampToNanos(
                                                               request.getTimestamp()));
                InvalidateResponse response =
                        InvalidateResponse.newBuilder().setSuccess(result)
                                .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            });
        }

    }
}