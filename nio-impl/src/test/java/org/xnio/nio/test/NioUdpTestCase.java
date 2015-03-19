/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xnio.nio.test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import junit.framework.TestCase;
import org.jboss.logging.Logger;
import org.junit.Ignore;
import org.xnio.Buffers;
import org.xnio.IoUtils;
import org.xnio.Xnio;
import org.xnio.OptionMap;
import org.xnio.ChannelListener;
import org.xnio.Options;
import org.xnio.XnioWorker;
import org.xnio.channels.MulticastSocketChannel;
import org.xnio.channels.SocketAddressBuffer;

/**
 * 
 * Test for UDP connections.
 * 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 *
 */
public final class NioUdpTestCase extends TestCase {
    private static final int SERVER_PORT = 12345;
    private static final InetSocketAddress SERVER_SOCKET_ADDRESS;
    private static final InetSocketAddress CLIENT_SOCKET_ADDRESS;

    private static final Logger log = Logger.getLogger("TEST");

    static {
        try {
            SERVER_SOCKET_ADDRESS = new InetSocketAddress(Inet4Address.getByAddress(new byte[] {127, 0, 0, 1}), SERVER_PORT);
            CLIENT_SOCKET_ADDRESS = new InetSocketAddress(Inet4Address.getByAddress(new byte[] {127, 0, 0, 1}), 0);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void doServerSideTest(final boolean multicast, final ChannelListener<MulticastSocketChannel> handler, final Runnable body) throws IOException {
        final Xnio xnio = Xnio.getInstance("nio");
        doServerSidePart(multicast, handler, body, xnio.createWorker(OptionMap.EMPTY));
    }

    private void doServerSidePart(final boolean multicast, final ChannelListener<MulticastSocketChannel> handler, final Runnable body, final XnioWorker worker) throws IOException {
        doPart(multicast, handler, body, SERVER_SOCKET_ADDRESS, worker);
    }

    private void doClientSidePart(final boolean multicast, final ChannelListener<MulticastSocketChannel> handler, final Runnable body, final XnioWorker worker) throws IOException {
        doPart(multicast, handler, body, CLIENT_SOCKET_ADDRESS, worker);
    }

    private synchronized void doPart(final boolean multicast, final ChannelListener<MulticastSocketChannel> handler, final Runnable body, final InetSocketAddress bindAddress, final XnioWorker worker) throws IOException {
        final MulticastSocketChannel server = worker.createUdpServer(bindAddress, handler, OptionMap.create(Options.MULTICAST, Boolean.valueOf(multicast)));
        try {
            body.run();
            server.close();
        } catch (RuntimeException e) {
            log.errorf(e, "Error running part");
            throw e;
        } catch (IOException e) {
            log.errorf(e, "Error running part");
            throw e;
        } catch (Error e) {
            log.errorf(e, "Error running part");
            throw e;
        } finally {
            IoUtils.safeClose(server);
        }
    }

    private synchronized void doClientServerSide(final boolean clientMulticast, final boolean serverMulticast, final ChannelListener<MulticastSocketChannel> serverHandler, final ChannelListener<MulticastSocketChannel> clientHandler, final Runnable body) throws IOException {
        final Xnio xnio = Xnio.getInstance("nio");
        final XnioWorker worker = xnio.createWorker(OptionMap.EMPTY);
        try {
            doServerSidePart(serverMulticast, serverHandler, new Runnable() {
                public void run() {
                    try {
                        doClientSidePart(clientMulticast, clientHandler, body, worker);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, worker);
        } finally {
            worker.shutdown();
            try {
                worker.awaitTermination(1L, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void doServerCreate(boolean multicast) throws Exception {
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean openedOk = new AtomicBoolean(false);
        final AtomicBoolean closedOk = new AtomicBoolean(false);
        doServerSideTest(multicast, new ChannelListener<MulticastSocketChannel>() {
            public void handleEvent(final MulticastSocketChannel channel) {
                channel.getCloseSetter().set(new ChannelListener<MulticastSocketChannel>() {
                    public void handleEvent(final MulticastSocketChannel channel) {
                        closedOk.set(true);
                        latch.countDown();
                    }
                });
                log.infof("In handleEvent for %s", channel);
                openedOk.set(true);
                latch.countDown();
            }
        }, new Runnable() {
            public void run() {
            }
        });
        assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
        assertTrue(openedOk.get());
        assertTrue(closedOk.get());
    }

    public void testServerCreate() throws Exception {
        log.info("Test: testServerCreate");
        doServerCreate(false);
    }

    public void testServerCreateMulticast() throws Exception {
        log.info("Test: testServerCreateMulticast");
        doServerCreate(true);
    }

    @SuppressWarnings("unused")
    @Ignore /* XXX - depends on each server getting a separate thread */
    public void testClientToServerTransmitNioToNio() throws Exception {
        if (true) return;
        log.info("Test: testClientToServerTransmitNioToNio");
        final AtomicBoolean clientOK = new AtomicBoolean(false);
        final AtomicBoolean serverOK = new AtomicBoolean(false);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch receivedLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(2);
        final byte[] payload = new byte[] { 10, 5, 15, 10, 100, -128, 30, 0, 0 };
        doClientServerSide(true, true, new ChannelListener<MulticastSocketChannel>() {
            public void handleEvent(final MulticastSocketChannel channel) {
                log.infof("In handleEvent for %s", channel);
                channel.getReadSetter().set(new ChannelListener<MulticastSocketChannel>() {
                    public void handleEvent(final MulticastSocketChannel channel) {
                        log.infof("In handleReadable for %s", channel);
                        try {
                            final ByteBuffer buffer = ByteBuffer.allocate(50);
                            final SocketAddressBuffer addressBuffer = new SocketAddressBuffer();
                            final int result = channel.receiveFrom(addressBuffer, buffer);
                            if (result == 0) {
                                log.infof("Whoops, spurious read notification for %s", channel);
                                channel.resumeReads();
                                return;
                            }
                            try {
                                final byte[] testPayload = new byte[payload.length];
                                Buffers.flip(buffer).get(testPayload);
                                log.infof("We received the packet on %s", channel);
                                assertTrue(Arrays.equals(testPayload, payload));
                                assertFalse(buffer.hasRemaining());
                                assertNotNull(addressBuffer.getSourceAddress());
                                try {
                                    channel.close();
                                    serverOK.set(true);
                                } finally {
                                    IoUtils.safeClose(channel);
                                }
                            } finally {
                                receivedLatch.countDown();
                                doneLatch.countDown();
                            }
                        } catch (IOException e) {
                            IoUtils.safeClose(channel);
                            throw new RuntimeException(e);
                        }
                    }
                });
                channel.resumeReads();
                startLatch.countDown();
            }
        }, new ChannelListener<MulticastSocketChannel>() {
            public void handleEvent(final MulticastSocketChannel channel) {
                log.infof("In handleEvent for %s", channel);
                channel.getWriteSetter().set(new ChannelListener<MulticastSocketChannel>() {
                    public void handleEvent(final MulticastSocketChannel channel) {
                        log.infof("In handleWritable for %s", channel);
                        try {
                            if (clientOK.get()) {
                                log.infof("Extra writable notification on %s (?!)", channel);
                            } else if (! channel.sendTo(SERVER_SOCKET_ADDRESS, ByteBuffer.wrap(payload))) {
                                log.infof("Whoops, spurious write notification for %s", channel);
                                channel.resumeWrites();
                            } else {
                                log.infof("We sent the packet on %s", channel);
                                try {
                                    assertTrue(receivedLatch.await(500000L, TimeUnit.MILLISECONDS));
                                    channel.close();
                                } finally {
                                    IoUtils.safeClose(channel);
                                }
                                clientOK.set(true);
                                doneLatch.countDown();
                            }
                        } catch (IOException e) {
                            IoUtils.safeClose(channel);
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                try {
                    // wait until server is ready
                    assertTrue(startLatch.await(500000L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                channel.resumeWrites();
            }
        }, new Runnable() {
            public void run() {
                try {
                    assertTrue(doneLatch.await(500000L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        assertTrue(clientOK.get());
        assertTrue(serverOK.get());
    }
}
