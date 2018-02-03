package pl.bbl.network.client;


import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import pl.bbl.network.packet.Packet;
import pl.bbl.network.server.handler.PacketDistributor;
import pl.bbl.network.server.handler.PacketHandler;

public class Client implements Runnable{
    private static final Object lock = new Object();
    private volatile ChannelFuture channelFuture;
    private PacketDistributor packetDistributor;
    private String host;
    private int port;

    public Client(String host, int port, PacketDistributor packetDistributor){
        this.host = host;
        this.port = port;
        this.packetDistributor = packetDistributor;
    }

    @Override
    public void run(){
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        synchronized (lock){
            try {
                prepareBootstrap(bootstrap, workerGroup);
                initializeChannelFeature(bootstrap);
                unlockPacketSending();
                closeConnectionAfterCloseCall();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                workerGroup.shutdownGracefully();
            }
        }
    }

    private void prepareBootstrap(Bootstrap bootstrap, EventLoopGroup workerGroup){
        bootstrap.group(workerGroup);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception { addHandlersToChannel(ch.pipeline());
            }
        });
    }

    protected void addHandlersToChannel(ChannelPipeline pipeline){
        pipeline.addLast(new ObjectEncoder(),
                new ObjectDecoder(ClassResolvers.cacheDisabled(null)), new PacketHandler(packetDistributor));
    }

    private void initializeChannelFeature(Bootstrap bootstrap) throws InterruptedException {
        channelFuture = bootstrap.connect(host, port).sync();
    }

    private synchronized void unlockPacketSending(){
        lock.notify();
    }

    private void closeConnectionAfterCloseCall() throws InterruptedException {
        channelFuture.channel().closeFuture().sync();
        channelFuture = null;
    }

    public void write(Packet packet){
        if(channelFuture == null)
            waitForChannelFutureAndSendPacket(packet);
        else{
            channelFuture.channel().writeAndFlush(packet);
        }
    }

    private void waitForChannelFutureAndSendPacket(Packet packet){
        new Thread(() -> {
            synchronized (lock){
                while(channelFuture == null){
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                write(packet);
            }
        }).start();
    }

    public boolean isConnected(){
        return channelFuture != null && channelFuture.channel().isOpen();
    }
}
