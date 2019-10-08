package com.rex.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * WebSocket server
 * TODO: Support TLS
 */
public class WsServer {

    private static final Logger sLogger = LoggerFactory.getLogger(WsServer.class);

    private final EventLoopGroup mBossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup mWorkerGroup = new NioEventLoopGroup(); // Default use Runtime.getRuntime().availableProcessors() * 2

    private final ServerBootstrap mBootstrap;
    private ChannelFuture mChannelFuture;

    private final List<WsConnection> mConnectionList = new ArrayList<>();

    public interface Callback {
        void onAdded(WsServer server, WsConnection conn);
        void onReceived(WsServer server, WsConnection conn, ByteBuffer data);
        void onRemoved(WsServer server, WsConnection conn);
    }
    private Callback mCallback;

    public static class Configuration {
        public String bindAddress;
        public int bindPort;
        public String sslCert;
        public String sslKey;
        public Configuration() {
        }
        public Configuration(String addr, int port) {
            bindAddress = addr;
            bindPort = port;
        }
        public Configuration(String addr, int port, String cert, String key) {
            this(addr, port);
            sslCert = cert;
            sslKey = key;
        }
    }
    private Configuration mConfig = new Configuration("0.0.0.0", 9787); // WSTP in T9 keyboard

    /**
     * Construct the server
     */
    public WsServer() {
        sLogger.trace("<init>");

        mBootstrap = new ServerBootstrap()
                .group(mBossGroup, mWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override // ChannelInitializer
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1 << 16)) // 65536
                                .addLast(new WsServerPathInterceptor(mConnCallback));
                    }
                })
                .childOption(ChannelOption.SO_KEEPALIVE, true);
    }

    synchronized public WsServer config(Configuration conf) {
        if (conf.bindAddress != null) mConfig.bindAddress = conf.bindAddress;
        if (conf.bindPort != 0) mConfig.bindPort = conf.bindPort;
        if (conf.sslKey != null) mConfig.sslKey = conf.sslKey;
        if (conf.sslCert != null) mConfig.sslCert = conf.sslCert;
        return this;
    }

    synchronized public WsServer config(InputStream in) {
        try {
            Properties config = new Properties();
            config.load(in);
            for (String name : config.stringPropertyNames()) {
                switch (name) {
                case "bindAddress": mConfig.bindAddress = config.getProperty(name); break;
                case "bindPort":    mConfig.bindPort = Integer.parseInt(config.getProperty(name)); break;
                case "sslKey":      mConfig.sslKey = config.getProperty(name); break;
                case "sslCert":     mConfig.sslCert = config.getProperty(name); break;
                }
            }
        } catch (IOException ex) {
            sLogger.warn("Failed to load config\n", ex);
        }
        return this;
    }

    /**
     * Start the websocket server
     */
    synchronized public WsServer start() {
        if (mChannelFuture != null) {
            sLogger.warn("already started");
            return this;
        }
        SocketAddress address = new InetSocketAddress(mConfig.bindAddress, mConfig.bindPort);
        sLogger.trace("start address:{}", address);
        mChannelFuture = mBootstrap.bind(address)
                .syncUninterruptibly();
        return this;
    }

    /**
     * Stop the websocket server
     */
    synchronized public WsServer stop() {
        sLogger.trace("stop");
        if (mChannelFuture == null) {
            sLogger.warn("not started");
            return this;
        }
        mChannelFuture.channel().close();
        mChannelFuture.channel().closeFuture().syncUninterruptibly();
        mChannelFuture = null;

        synchronized (mConnectionList) {
            for (WsConnection conn : mConnectionList) {
                conn.close();
            }
        }
        return this;
    }

    public int port() {
        return mConfig.bindPort;
    }

    public WsServer setCallback(Callback cb) {
        mCallback = cb;
        return this;
    }

    private WsConnection.Callback mConnCallback = new WsConnection.Callback() {
        @Override
        public void onConnected(WsConnection conn) {
            sLogger.trace("connection {} connect", conn);
            synchronized (mConnectionList) {
                mConnectionList.add(conn);
            }
            if (mCallback != null) {
                mCallback.onAdded(WsServer.this, conn);
            }
        }
        @Override
        public void onReceived(WsConnection conn, ByteBuffer data) {
            sLogger.trace("connection {} receive {}", conn, data.remaining());
            if (mCallback != null) {
                mCallback.onReceived(WsServer.this, conn, data);
            }
        }
        @Override
        public void onDisconnected(WsConnection conn) {
            sLogger.trace("connection {} disconnect", conn);
            synchronized (mConnectionList) {
                mConnectionList.remove(conn);
            }
            if (mCallback != null) {
                mCallback.onRemoved(WsServer.this, conn);
            }
        }
    };

    public static void main(String[] args) {
        WsServer server = new WsServer();
        Configuration config = new Configuration();
        int idx = 0;
        while (idx < args.length) {
            String key = args[idx++];
            if ("-a".equals(key) || "--addr".equals(key)) {
                config.bindAddress = args[idx++];
            }
            if ("-p".equals(key) || "--port".equals(key)) {
                try {
                    config.bindPort = Integer.parseInt(args[idx++]);
                } catch (NumberFormatException ex) {
                    sLogger.warn("Failed to parse port\n", ex);
                }
            }
            if ("-c".equals(key) || "--config".equals(key)) {
                String configFileName = args[idx++];
                try {
                    server.config(new FileInputStream(configFileName));
                } catch (FileNotFoundException ex) {
                    sLogger.warn("Failed to load config file " + configFileName + "\n", ex);
                }
            }
            if ("-h".equals(key) || "--help".equals(key)) {
                System.out.println("Usage: WsTunnelServer [options]");
                System.out.println("    -a | --addr     Socket bind address, default 0.0.0.0");
                System.out.println("    -p | --port     Socket bind port, default 5081");
                System.out.println("    -c | --config   Configuration file");
                System.out.println("    -h | --help     Help page");
                return;
            }
        }
    }
}
