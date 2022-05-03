/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            //判断服务端连接接收方向是否关闭，这里肯定是没有关闭的
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    //TCP连接半关闭处理逻辑
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                   //如果不支持半关闭，则服务端直接调用close方法向客户端发送fin,结束close_wait状态进如last_ack状态
                    close(voidPromise());
                }
            } else if (!inputClosedSeenErrorOnRead) {
                //TCP连接半关闭处理逻辑
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();

//            处理半关闭相关代码
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            //    private volatile ByteBufAllocator allocator = ByteBufAllocator.DEFAULT(PooledByteBufAllocator);
            final ByteBufAllocator allocator = config.getAllocator();

//            lastBytesRead < 0：表示客户端主动发起了连接关闭流程，Netty开始连接关闭处理流程
//            lastBytesRead = 0：表示当前NioSocketChannel上的数据已经全部读取完毕，没有数据可读了
//            当lastBytesRead > 0：表示在本次read loop中从NioSocketChannel中读取到了数据，
//            会在NioSocketChannel的pipeline中触发ChannelRead事件。
//            进而在pipeline中负责IO处理的ChannelHandelr中响应，处理网络请求。

            //自适应ByteBuf分配器 AdaptiveRecvByteBufAllocator ,用于动态调节ByteBuf容量
            //需要与具体的ByteBuf分配器配合使用 比如这里的PooledByteBufAllocator
            //AdaptiveRecvByteBufAllocator 负责动态计算大小，PooledByteBufAllocator负责分配
//            MaxMessageHandle
            //private volatile RecvByteBufAllocator rcvBufAllocator (AdaptiveRecvByteBufAllocator);
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();

            //allocHandler用于统计每次读取数据的大小，方便下次分配合适大小的ByteBuf
            //重置清除上次的统计指标
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    //利用PooledByteBufAllocator分配合适 大小的byteBuf 初始大小为2048
                    //AbstractByteBufAllocator
                    byteBuf = allocHandle.allocate(allocator);
                    //记录本次读取了多少字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    //如果本次没有读取到任何字节，则退出循环 进行下一轮事件轮询
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            //......表示客户端发起连接关闭.....
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }
                    //read loop读取数据次数+1
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    //ChannelRead 每次读取都会触发
                    //客户端NioSocketChannel的pipeline中触发ChannelRead事件

                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
//                    是否结束本次read loop循
                } while (allocHandle.continueReading());

//                根据本轮read loop总共读取到的字节数totalBytesRead
//                来决定是否对用于接收下一轮OP_READ事件数据的ByteBuffer进行扩容或者缩容。
                allocHandle.readComplete();

                //在NioSocketChannel的pipeline中触发ChannelReadComplete事件，表示一次read事件处理完毕
                //但这并不表示 客户端发送来的数据已经全部读完，因为如果数据太多的话，
                // 这里只会读取16次，剩下的会等到下次read事件到来后在处理

                //ChannelReadComplete 读取完成或者16次才触发
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            //文件已经传输完毕
            //表示当前 FileRegion 中的文件数据已经传输完毕。
            // 那么在这种情况下本次 write loop 没有写入任何数据到 Socket ，所以返回 0 ，
            // writeSpinCount - 0 意思就是本次 write loop 不算，继续循环
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }
            //零拷贝的方式传输文件
            //系统调用为 sendFile 实现零拷贝网络文件的传输。
            //表示本 write loop 中写入了一些数据到 Socket 中，会有返回 1，
            // writeSpinCount - 1 减少一次 write loop 次数。
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        //走到这里表示 此时Socket已经写不进去了 退出writeLoop，注册OP_WRITE事件
//        表示当前 Socket 发送缓冲区已满，无法写入数据，那么就返回 WRITE_STATUS_SNDBUF_FULL = Integer.MAX_VALUE。
//        writeSpinCount - Integer.MAX_VALUE 必然是负数，直接退出循环，
//        向 Reactor 注册 OP_WRITE 事件并退出 flush 流程。等 Socket 发送缓冲区可写了，
//        Reactor 会通知 channel 继续发送文件数据
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        //最大写入次数 默认为16 目的是为了保证SubReactor可以平均的处理注册其上的所有Channel
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

//            在网络数据传输的过程中，Netty为了减少数据从 堆内内存到堆外内存的拷贝以及缓解GC的压力
//            所以这里必须采用 DirectByteBuffer 使用堆外内存来存放网络发送数据
            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        //注意：只有当 Socket 缓冲区已满导致无法写入时，Netty 才会去注册 OP_WRITE 事件。
        // 这和我们之前介绍的 OP_ACCEPT 事件和 OP_READ 事件的注册时机是不同的。
        // Did not write completely.
        if (setOpWrite) {
//            /这里处理还没写满16次 但是socket缓冲区已满写不进去的情况 注册write事件
//            什么时候socket可写了， epoll会通知reactor线程继续写
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.

            // 这里处理的是socket缓冲区依然可写，但是写了16次还没写完，这时就不能在写了，
            // reactor线程需要处理其他channel上的io事件
            //因为此时socket是可写的，必须清除op_write事件，否则会一直不停地被通知
            clearOpWrite();

            //如果本次writeLoop还没写完，则提交flushTask到reactor
            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
