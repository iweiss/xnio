/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xnio.channels;

import java.nio.channels.GatheringByteChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.Flushable;
import java.util.concurrent.TimeUnit;
import org.xnio.Buffers;

/**
 * A blocking wrapper for a {@code StreamChannel}.  Write operations will block until some data may be transferred.
 * Once any amount of data is written, the operation will return.  If a write timeout is specified, then the write methods
 * will throw a {@link WriteTimeoutException} if the timeout expires without writing any data.
 */
public class BlockingWritableByteChannel implements GatheringByteChannel, Flushable {
    private final StreamSinkChannel delegate;
    private volatile long writeTimeout;

    /**
     * Construct a new instance.
     *
     * @param delegate the channel to forward I/O operations to
     */
    public BlockingWritableByteChannel(final StreamSinkChannel delegate) {
        this.delegate = delegate;
    }

    /**
     * Construct a new instance.
     *
     * @param delegate the channel to forward I/O operations to
     * @param writeTimeout the write timeout
     * @param writeTimeoutUnit the write timeout unit
     */
    public BlockingWritableByteChannel(final StreamSinkChannel delegate, final long writeTimeout, final TimeUnit writeTimeoutUnit) {
        if (writeTimeout < 0L) {
            throw new IllegalArgumentException("Negative write timeout");
        }
        this.delegate = delegate;
        final long calcTimeout = writeTimeoutUnit.toNanos(writeTimeout);
        this.writeTimeout = writeTimeout == 0L ? 0L : calcTimeout < 1L ? 1L : calcTimeout;
    }

    /**
     * Set the write timeout.
     *
     * @param writeTimeout the write timeout
     * @param writeTimeoutUnit the write timeout unit
     */
    public void setWriteTimeout(long writeTimeout, TimeUnit writeTimeoutUnit) {
        if (writeTimeout < 0L) {
            throw new IllegalArgumentException("Negative write timeout");
        }
        final long calcTimeout = writeTimeoutUnit.toNanos(writeTimeout);
        this.writeTimeout = writeTimeout == 0L ? 0L : calcTimeout < 1L ? 1L : calcTimeout;
    }

    /**
     * Perform a blocking, gathering write operation.
     *
     * @param srcs the source buffers
     * @param offset the offset into the destination buffer array
     * @param length the number of buffers to write from
     * @return the number of bytes actually written (will be greater than zero)
     * @throws IOException if an I/O error occurs
     */
    public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
        if (!Buffers.hasRemaining(srcs, offset, length)) {
            return 0L;
        }
        final StreamSinkChannel delegate = this.delegate;
        long res;
        if ((res = delegate.write(srcs, offset, length)) == 0L) {
            long start = System.nanoTime();
            long elapsed = 0L, writeTimeout;
            do {
                writeTimeout = this.writeTimeout;
                if (writeTimeout == 0L || writeTimeout == Long.MAX_VALUE) {
                    delegate.awaitWritable();
                } else if (writeTimeout <= elapsed) {
                    throw new WriteTimeoutException("Write timed out");
                } else {
                    delegate.awaitWritable(writeTimeout - elapsed, TimeUnit.NANOSECONDS);
                }
                elapsed = System.nanoTime() - start;
            } while ((res = delegate.write(srcs, offset, length)) == 0L);
        }
        return res;
    }

    /**
     * Perform a blocking, gathering write operation.
     *
     * @param srcs the source buffers
     * @return the number of bytes actually written (will be greater than zero)
     * @throws IOException if an I/O error occurs
     */
    public long write(final ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }

    /**
     * Perform a blocking write operation.
     *
     * @param src the source buffer
     * @return the number of bytes actually written (will be greater than zero)
     * @throws IOException if an I/O error occurs
     */
    public int write(final ByteBuffer src) throws IOException {
        if (! src.hasRemaining()) {
            return 0;
        }
        final StreamSinkChannel delegate = this.delegate;
        int res;
        if ((res = delegate.write(src)) == 0L) {
            long start = System.nanoTime();
            long elapsed = 0L, writeTimeout;
            do {
                writeTimeout = this.writeTimeout;
                if (writeTimeout == 0L || writeTimeout == Long.MAX_VALUE) {
                    delegate.awaitWritable();
                } else if (writeTimeout <= elapsed) {
                    throw new WriteTimeoutException("Write timed out");
                } else {
                    delegate.awaitWritable(writeTimeout - elapsed, TimeUnit.NANOSECONDS);
                }
                elapsed = System.nanoTime() - start;
            } while ((res = delegate.write(src)) == 0L);
        }
        return res;
    }

    /** {@inheritDoc} */
    public boolean isOpen() {
        return delegate.isOpen();
    }

    /** {@inheritDoc} */
    public void flush() throws IOException {
        final StreamSinkChannel delegate = this.delegate;
        if (! delegate.flush()) {
            long start = System.nanoTime();
            long elapsed = 0L, writeTimeout;
            do {
                writeTimeout = this.writeTimeout;
                if (writeTimeout == 0L || writeTimeout == Long.MAX_VALUE) {
                    delegate.awaitWritable();
                } else if (writeTimeout <= elapsed) {
                    throw new WriteTimeoutException("Flush timed out");
                } else {
                    delegate.awaitWritable(writeTimeout - elapsed, TimeUnit.NANOSECONDS);
                }
                elapsed = System.nanoTime() - start;
            } while (! delegate.flush());
        }
    }

    /** {@inheritDoc} */
    public void close() throws IOException {
        delegate.close();
    }
}
