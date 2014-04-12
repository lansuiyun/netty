/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.DatagramChannelConfig;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public final class EpollDatagramChannel extends AbstractEpollChannel {
    private volatile InetSocketAddress local;
    private final EpollDatagramChannelConfig config;
    public EpollDatagramChannel() {
        super(Native.socketDgramFd(), Native.EPOLLIN);
        config = new EpollDatagramChannelConfig(this);
    }

    @Override
    protected AbstractEpollUnsafe newUnsafe() {
        return new EpollDatagramChannelUnsafe();
    }

    @Override
    protected InetSocketAddress localAddress0() {
        return local;
    }

    @Override
    protected InetSocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        InetSocketAddress addr = (InetSocketAddress) localAddress;
        checkResolvable(addr);
        Native.bind(fd, addr.getAddress(), addr.getPort());
        local = Native.localAddress(fd);
        active = true;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
    }

    @Override
    public EpollDatagramChannelConfig config() {
        return config;
    }

    final class EpollDatagramChannelUnsafe extends AbstractEpollUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;
        @Override
        public void connect(SocketAddress socketAddress, SocketAddress socketAddress2, ChannelPromise channelPromise) {
            // Connect not supported by ServerChannel implementations
            channelPromise.setFailure(new UnsupportedOperationException());
        }

        @Override
        void epollInReady() {
            DatagramChannelConfig config = config();
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }

            assert eventLoop().inEventLoop();
            final ChannelPipeline pipeline = pipeline();
            Throwable exception = null;
            try {
                try {
                    for (;;) {
                        boolean free = true;
                        ByteBuf data = allocHandle.allocate(config.getAllocator());
                        ByteBuffer nioData = data.internalNioBuffer(data.writerIndex(), data.writableBytes());
                        DatagramSocketAddress remoteAddress = Native.readFrom(
                                fd, nioData, nioData.position(), nioData.limit());
                        if (remoteAddress == null) {
                            break;
                        }

                        int readBytes = remoteAddress.receivedAmount;
                        data.writerIndex(data.writerIndex() + readBytes);
                        allocHandle.record(readBytes);
                        try {
                            readPending = false;
                            pipeline.fireChannelRead(
                                    new DatagramPacket(data, (InetSocketAddress) localAddress(), remoteAddress));
                            free = false;
                        } catch (Throwable t) {
                            // keep on reading as we use epoll ET and need to consume everything from the socket
                            pipeline.fireChannelReadComplete();
                            pipeline.fireExceptionCaught(t);
                        } finally {
                            if (free) {
                                data.release();
                            }
                        }
                    }
                } catch (Throwable t) {
                    exception = t;
                }
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    pipeline.fireExceptionCaught(exception);
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!config().isAutoRead() && !readPending) {
                    clearEpollIn();
                }
            }
        }
    }

    static final class DatagramSocketAddress extends InetSocketAddress {
        final int receivedAmount;

        DatagramSocketAddress(InetAddress addr, int port, int receivedAmount) {
            super(addr, port);
            this.receivedAmount = receivedAmount;
        }
    }
}
