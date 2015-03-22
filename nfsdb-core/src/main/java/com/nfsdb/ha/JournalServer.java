/*
 * Copyright (c) 2014-2015. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.ha;

import com.nfsdb.JournalKey;
import com.nfsdb.JournalWriter;
import com.nfsdb.collections.ObjIntHashMap;
import com.nfsdb.exceptions.ClusterLossException;
import com.nfsdb.exceptions.JournalDisconnectedChannelException;
import com.nfsdb.exceptions.JournalNetworkException;
import com.nfsdb.factory.JournalReaderFactory;
import com.nfsdb.ha.auth.AuthorizationHandler;
import com.nfsdb.ha.bridge.JournalEventBridge;
import com.nfsdb.ha.config.ServerConfig;
import com.nfsdb.ha.mcast.OnDemandAddressSender;
import com.nfsdb.ha.model.IndexedJournalKey;
import com.nfsdb.logging.Logger;
import com.nfsdb.utils.NamedDaemonThreadFactory;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class JournalServer {

    private static final Logger LOGGER = Logger.getLogger(JournalServer.class);
    private final AtomicInteger writerIdGenerator = new AtomicInteger(0);
    private final ObjIntHashMap<JournalWriter> writers = new ObjIntHashMap<>();
    private final JournalReaderFactory factory;
    private final JournalEventBridge bridge;
    private final ServerConfig config;
    private final ThreadPoolExecutor service;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<SocketChannelHolder> channels = new CopyOnWriteArrayList<>();
    private final OnDemandAddressSender addressSender;
    private final AuthorizationHandler authorizationHandler;
    private final JournalServerLogger serverLogger = new JournalServerLogger();
    private final int serverInstance;
    private final AtomicBoolean alpha = new AtomicBoolean(false);
    private ServerSocketChannel serverSocketChannel;

    public JournalServer(JournalReaderFactory factory) {
        this(new ServerConfig(), factory);
    }

    public JournalServer(JournalReaderFactory factory, AuthorizationHandler authorizationHandler) {
        this(new ServerConfig(), factory, authorizationHandler);
    }

    public JournalServer(ServerConfig config, JournalReaderFactory factory) {
        this(config, factory, null);
    }

    public JournalServer(ServerConfig config, JournalReaderFactory factory, AuthorizationHandler authorizationHandler) {
        this(config, factory, authorizationHandler, 0);
    }

    public JournalServer(ServerConfig config, JournalReaderFactory factory, AuthorizationHandler authorizationHandler, int instance) {
        this.config = config;
        this.factory = factory;
        this.service = new ThreadPoolExecutor(
                0
                , Integer.MAX_VALUE
                , 60L
                , TimeUnit.SECONDS
                , new SynchronousQueue<Runnable>()
                , new NamedDaemonThreadFactory("nfsdb-server-" + instance + "-agent", true)
        );
        this.bridge = new JournalEventBridge(config.getHeartbeatFrequency(), TimeUnit.MILLISECONDS);
        if (config.isMultiCastEnabled()) {
            this.addressSender = new OnDemandAddressSender(config, 230, 235, instance);
        } else {
            this.addressSender = null;
        }
        this.authorizationHandler = authorizationHandler;
        this.serverInstance = instance;
    }

    public JournalEventBridge getBridge() {
        return bridge;
    }

    public int getConnectedClients() {
        return channels.size();
    }

    public JournalReaderFactory getFactory() {
        return factory;
    }

    public JournalServerLogger getLogger() {
        return serverLogger;
    }

    public int getServerInstance() {
        return serverInstance;
    }

    public void halt(long timeout, TimeUnit unit) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        LOGGER.info("Stopping agent services %d", serverInstance);
        service.shutdown();

        LOGGER.info("Stopping acceptor");
        try {
            serverSocketChannel.close();
        } catch (IOException e) {
            LOGGER.debug("Error closing socket", e);
        }


        if (timeout > 0) {
            try {
                LOGGER.info("Waiting for %s agent services to complete data exchange on %s", service.getActiveCount(), serverInstance);
                service.awaitTermination(timeout, unit);
            } catch (InterruptedException e) {
                LOGGER.debug("Interrupted wait", e);
            }
        }

        LOGGER.info("Stopping bridge on %d", serverInstance);
        bridge.halt();

        if (addressSender != null) {
            LOGGER.info("Stopping mcast sender on %d", serverInstance);
            addressSender.halt();
        }

        LOGGER.info("Closing channels on %d", serverInstance);
        closeChannels();

        LOGGER.info("Stopping logger on %d", serverInstance);
        serverLogger.halt();

        try {
            if (timeout > 0) {
                LOGGER.info("Waiting for %s  agent services to stop on %s", service.getActiveCount(), serverInstance);
                service.awaitTermination(timeout, unit);
            }
            LOGGER.info("Server %d is shutdown", serverInstance);
        } catch (InterruptedException e) {
            LOGGER.info("Server %d is shutdown, but some connections are still lingering.", serverInstance);
        }

    }

    public void halt() {
        halt(30, TimeUnit.SECONDS);
    }

    public boolean isAlpha() {
        return alpha.get();
    }

    public void setAlpha(boolean ignore) {
        alpha.set(ignore);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void publish(JournalWriter journal) {
        writers.put(journal, writerIdGenerator.getAndIncrement());
    }

    public void start() throws JournalNetworkException {
        serverLogger.start();
        for (ObjIntHashMap.Entry<JournalWriter> e : writers) {
            JournalEventPublisher publisher = new JournalEventPublisher(e.value, bridge);
            e.key.setTxListener(publisher);
        }

        serverSocketChannel = config.openServerSocketChannel(serverInstance);
        if (config.isMultiCastEnabled()) {
            addressSender.start();
        }
        bridge.start();
        running.set(true);
        service.execute(new Acceptor());
    }

    @SuppressWarnings("unchecked")
    IndexedJournalKey getWriterIndex0(JournalKey key) {
        for (ObjIntHashMap.Entry<JournalWriter> e : writers.immutableIterator()) {
            JournalKey jk = e.key.getKey();
            if (jk.derivedLocation().equals(key.derivedLocation())) {
                return new IndexedJournalKey(e.value, new JournalKey(jk.getId(), jk.getModelClass(), jk.getLocation(), jk.getRecordHint()));
            }
        }
        return null;
    }

    private void addChannel(SocketChannelHolder holder) {
        channels.add(holder);
    }

    private void closeChannel(SocketChannelHolder holder, boolean force) {
        if (holder != null) {
            try {
                if (holder.socketAddress != null) {
                    if (force) {
                        LOGGER.info("Server node %d: Client forced out: %s", serverInstance, holder.socketAddress);
                    } else {
                        LOGGER.info("Server node %d: Client disconnected: %s", serverInstance, holder.socketAddress);
                    }
                }
                holder.byteChannel.close();

            } catch (IOException e) {
                LOGGER.error("Server node %d: Cannot close channel [%s]: %s", serverInstance, holder.byteChannel, e.getMessage());
            }
        }
    }

    private void closeChannels() {
        while (channels.size() > 0) {
            closeChannel(channels.remove(0), true);
        }
    }

    private void removeChannel(SocketChannelHolder holder) {
        if (channels.remove(holder)) {
            closeChannel(holder, false);
        }
    }

    private class Acceptor implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    if (!running.get()) {
                        break;
                    }
                    SocketChannel channel = serverSocketChannel.accept();
                    if (channel != null) {
                        SocketChannelHolder holder = new SocketChannelHolder(
                                config.getSslConfig().isSecure() ? new SecureByteChannel(channel, config.getSslConfig()) : channel
                                , channel.getRemoteAddress()
                        );
                        addChannel(holder);
                        try {
                            service.submit(new Handler(holder));
                            LOGGER.info("Server node %d: Connected %s", serverInstance, holder.socketAddress);
                        } catch (RejectedExecutionException e) {
                            LOGGER.info("Node %d ignoring connection from %s. Server is shutting down.", serverInstance, holder.socketAddress);
                        }
                    }
                }
            } catch (IOException | JournalNetworkException e) {
                if (running.get()) {
                    LOGGER.error("Acceptor dying", e);
                }
            }
            LOGGER.info("Acceptor shutdown on %s", serverInstance);
        }
    }

    class Handler implements Runnable {

        private final JournalServerAgent agent;
        private final SocketChannelHolder holder;

        Handler(SocketChannelHolder holder) {
            this.holder = holder;
            this.agent = new JournalServerAgent(JournalServer.this, holder.socketAddress, authorizationHandler);
        }

        @Override
        public void run() {
            boolean haltServer = false;
            try {
                while (true) {
                    if (!running.get()) {
                        break;
                    }
                    try {
                        agent.process(holder.byteChannel);
                    } catch (JournalDisconnectedChannelException e) {
                        break;
                    } catch (ClusterLossException e) {
                        haltServer = true;
                        LOGGER.info("Server node %s lost cluster vote to %s", serverInstance, e.getInstance());
                        break;
                    } catch (JournalNetworkException e) {
                        if (running.get()) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Client died", e);
                            } else {
                                LOGGER.info("Server node %d: Client died %s: %s", serverInstance, holder.socketAddress, e.getMessage());
                            }
                        }
                        break;
                    } catch (Throwable e) {
                        LOGGER.error("Unhandled exception in server process", e);
                        if (e instanceof Error) {
                            throw e;
                        }
                        break;
                    }
                }
            } finally {
                agent.close();
                removeChannel(holder);
            }

            if (haltServer) {
                halt(0, TimeUnit.SECONDS);
            }
        }
    }
}
