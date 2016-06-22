/*
 * Copyright 2016 The Netty Project
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
package io.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.internal.ThreadLocalRandom;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AutoFlushTest {

    public static TestEnvironment environment;

    @BeforeClass
    public static void setUp() throws Exception {
        environment = new TestEnvironment((SingleThreadEventLoop) new NioEventLoopGroup().next(),
                                          (SingleThreadEventLoop) new NioEventLoopGroup().next(),
                                          NioServerSocketChannel.class, NioSocketChannel.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        environment.shutdown();
    }

    @Test(timeout = 20000)
    public void testSingleWrite() throws Exception {
        environment.connect();
        environment.pipeline.write(newDataBuffer()).sync();
    }

    @Test(timeout = 20000)
    public void testWithWriteOnContext() throws Exception {
        environment.connect();
        environment.pipeline.firstContext().write(newDataBuffer()).sync();
    }

    @Test(timeout = 20000)
    public void testWriteFromFlush() throws Exception {
        environment.connect();
        environment.clientChannel.pipeline().addLast(new ChannelDuplexHandler() {
            private boolean written;
            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                if (!written) {
                    written = true;
                    environment.pipeline.write(newDataBuffer());
                }
                super.flush(ctx);
            }
        });
        environment.pipeline.write(newDataBuffer()).sync();
    }

    @Test(timeout = 20000)
    public void testMultipleWrites() throws Exception {
        environment.connect();
        final ChannelPromise aggreggatedPromise = environment.clientChannel.newPromise();
        ByteBuf data = newDataBuffer();
        PromiseCombiner promiseCombiner = new PromiseCombiner();
        for (int i = 0; i < 10; i++) {
            ChannelPromise promise = environment.clientChannel.newPromise();
            promiseCombiner.add(promise);
            environment.clientChannel.write(data.retainedDuplicate(), promise);
        }
        promiseCombiner.finish(aggreggatedPromise);
        aggreggatedPromise.sync();
    }

    @Test(timeout = 20000)
    public void testFromWithinEventloop() throws Exception {
        environment.connect();
        final AtomicReference<ChannelFuture> writeResult = new AtomicReference<ChannelFuture>();
        environment.clientChannel.eventLoop().submit(new Runnable() {
            @Override
            public void run() {
                writeResult.set(environment.pipeline.write(newDataBuffer()));
            }
        }).sync();

        writeResult.get().sync();
    }

    @Test(timeout = 20000)
    public void testFromWithinEventloopCorrectContext() throws Throwable {
        environment.connect();
        final AtomicReference<ChannelFuture> writeResult = new AtomicReference<ChannelFuture>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        environment.clientChannel.eventLoop().submit(new Runnable() {
            @Override
            public void run() {
                environment.clientChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {

                    @Override
                    public void flush(ChannelHandlerContext ctx) throws Exception {
                        error.set(new AssertionError("Should not call flush of this context"));
                    }

                    @Override
                    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                        writeResult.set(ctx.write(newDataBuffer()));
                    }
                });
            }
        }).sync();

        writeResult.get().sync();
        Throwable cause = error.get();
        if (cause != null) {
            throw cause;
        }
    }

    @Test(timeout = 20000)
    public void testOnlyOneFlushDone() throws Throwable {
        environment.connect();
        final AtomicReference<ChannelFuture> writeResult = new AtomicReference<ChannelFuture>();
        final AtomicReference<ChannelFuture> writeResult2 = new AtomicReference<ChannelFuture>();
        final AtomicReference<ChannelFuture> writeResult3 = new AtomicReference<ChannelFuture>();

        final AtomicInteger flushCount = new AtomicInteger();
        final AtomicInteger writeCount = new AtomicInteger();

        environment.clientChannel.eventLoop().submit(new Runnable() {
            @Override
            public void run() {
                environment.clientChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {

                    @Override
                    public void flush(ChannelHandlerContext ctx) throws Exception {
                        flushCount.incrementAndGet();
                        writeResult3.set(ctx.write(newDataBuffer()).addListener(ChannelFutureListener.CLOSE));
                        super.flush(ctx);
                    }
                });
                environment.clientChannel.pipeline().addLast(new ChannelOutboundHandlerAdapter());
                environment.clientChannel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        if (writeCount.incrementAndGet() == 1) {
                            if (flushCount.get() != 0) {
                                promise.setFailure(new AssertionError("flushCount > 0"));
                            }
                        }
                        super.write(ctx, msg, promise);
                    }
                });
                writeResult.set(environment.clientChannel.write(newDataBuffer()));
                writeResult2.set(environment.clientChannel.write(newDataBuffer()));
            }
        }).sync();

        writeResult.get().sync();
        writeResult2.get().sync();
        writeResult3.get().sync();
        environment.clientChannel.closeFuture().syncUninterruptibly();

        // We do two writes but only expect one flush.
        Assert.assertEquals(1, flushCount.get());
        Assert.assertEquals(2, writeCount.get());
    }

    private static ByteBuf newDataBuffer() {
        byte[] dataArr = new byte[32];
        ThreadLocalRandom.current().nextBytes(dataArr);
        return Unpooled.wrappedBuffer(dataArr);
    }

    public static final class TestEnvironment {

        private final SingleThreadEventLoop serverEventloop;
        private final SingleThreadEventLoop clientEventloop;
        private final ServerBootstrap serverBootstrap;
        private final Bootstrap bootstrap;
        private ChannelPipeline pipeline;
        private Channel clientChannel;
        private Channel serverChannel;

        private TestEnvironment(SingleThreadEventLoop serverEventloop, SingleThreadEventLoop clientEventloop,
                                Class<? extends ServerChannel> serverChannelClass,
                                Class<? extends Channel> clientChannelClass) {
            this.serverEventloop = serverEventloop;
            this.clientEventloop = clientEventloop;
            serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(serverEventloop)
                           .channel(serverChannelClass)
                           .childHandler(new ChannelInitializer<Channel>() {
                               @Override
                               protected void initChannel(Channel ch) throws Exception {
                                   ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                       @Override
                                       public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                           // Consume message
                                           ReferenceCountUtil.release(msg);
                                       }
                                   });
                               }
                           });
            bootstrap = new Bootstrap();
            bootstrap.option(ChannelOption.AUTO_FLUSH, true)
                     .group(clientEventloop)
                     .channel(clientChannelClass)
                     .handler(new ChannelInitializer<Channel>() {
                         @Override
                         protected void initChannel(Channel ch) throws Exception {
                             ch.pipeline().addFirst(new LoggingHandler());
                         }
                     });
        }

        public void connect() {
            ChannelFuture bind = serverBootstrap.bind(0);
            SocketAddress serverAddr;
            try {
                bind.sync();
                serverChannel = bind.channel();
                serverAddr = serverChannel.localAddress();
                ChannelFuture clientChannelFuture = bootstrap.connect(serverAddr);
                clientChannelFuture.sync();
                clientChannel = clientChannelFuture.channel();
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            }
            pipeline = clientChannel.pipeline();
            if (!clientChannel.config().getOption(ChannelOption.AUTO_FLUSH)) {
                throw new IllegalStateException("Auto-flush not set.");
            }
        }

        private void shutdown() throws InterruptedException {
            clientChannel.close().sync();
            serverChannel.close().sync();
            serverEventloop.shutdownGracefully();
            clientEventloop.shutdownGracefully();
        }
    }
}
