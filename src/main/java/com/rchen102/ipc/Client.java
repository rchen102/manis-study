package com.rchen102.ipc;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.rchen102.conf.CommonConfigurationKeysPublic;
import com.rchen102.conf.Configuration;
import com.rchen102.io.IOUtils;
import com.rchen102.io.Writable;
import com.rchen102.ipc.protobuf.IpcConnectionContextProtos;
import com.rchen102.ipc.protobuf.RpcHeaderProtos.RpcRequestHeaderProto;
import com.rchen102.net.NetUtils;
import com.rchen102.util.ProtoUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.net.SocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Client {
    private static final Log LOG = LogFactory.getLog(Client.class);

    /** A counter for generating call IDs. */
    private static final AtomicInteger callIdCounter = new AtomicInteger();

    /** 网络连接的缓存池 */
    private final Hashtable<ConnectionId, Connection> connections = new Hashtable<>();

    private Class<? extends Writable> valueClass;
    private AtomicBoolean running = new AtomicBoolean(true); // Client 是否还在运行
    final private Configuration conf;
    /** 创建 socket 的方式 */
    private SocketFactory socketFactory;
    private final int connectionTimeOut; // 与服务建立连接的超时时间
    private final byte[] clientId;

    // 代表发送调用请求的线程池
    private final ExecutorService sendParamsExecutor;
    private final static ClientExecutorServiceFactory clientExecutorFactory =
            new ClientExecutorServiceFactory();

    private static class ClientExecutorServiceFactory {
        private int executorRefCount = 0;
        private ExecutorService clientExecutor = null;

        /**
         * 如果内部引用计数器（executorRefCount）为 0，初始化
         * 否则直接返回 Executor
         * 为了保证唯一性，调用该函数时需要加锁
         * @return ExecutorService 实例
         */
        synchronized ExecutorService refAndGetInstance() {
            if (executorRefCount == 0) {
                clientExecutor = Executors.newCachedThreadPool(
                        new ThreadFactoryBuilder().setDaemon(true)
                                .setNameFormat("IPC Parameter Sending Thread #%d")
                                .build());
            }
            executorRefCount++;
            return clientExecutor;
        }

        synchronized void unrefAndCleanup() {
            executorRefCount--;
            assert executorRefCount >= 0;

            if (executorRefCount == 0) {
                clientExecutor.shutdown();
                /**
                 * 一分钟后如果仍然没有关闭或者在等待过程中被中断，
                 * 则调用 {@link ExecutorService#shutdownNow()}
                 */
                try {
                    if (!clientExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                        clientExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    LOG.error("Interrupted while waiting for clientExecutor" +
                            "to stop", e);
                    clientExecutor.shutdownNow();
                }

                clientExecutor = null;
            }
        }


    }

    /**
     *
     * @param valueClass 调用的返回类型
     * @param conf 配置对象
     * @param factory socket 工厂
     */
    public Client(Class<? extends Writable> valueClass, Configuration conf,
                  SocketFactory factory) {
        this.valueClass = valueClass;
        this.conf = conf;
        this.socketFactory = factory;
        this.connectionTimeOut = conf.getInt(CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_TIMEOUT_KEY,
                CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_TIMEOUT_DEFAULT);
        this.clientId = ClientId.getClientId();
        this.sendParamsExecutor = clientExecutorFactory.refAndGetInstance();
    }

    public void stop() {

    }

    /**
     * 代表 rpc 调用的类，rpc调用单元
     * 封装之前创建的 RPC调用请求的封装类 和 RPC返回值的封装类
     */
    static class Call {
        final int id;
        Writable rpcRequest;
        Writable rpcResponse;
        IOException error;
        final RPC.RpcKind rpcKind;
        boolean done; // 本次请求是否完成

        private Call(RPC.RpcKind rpcKind, Writable rpcRequest) {
            this.rpcKind = rpcKind;
            this.rpcRequest = rpcRequest;

            this.id = nextCallId();  // 获取一个 id，用于唯一标识当前的 Call 对象
        }
    }

    /**
     * 返回自增的 id，由于存在线程安全问题，因此 counter 是 atomic 类型
     * 可能会被多个客户端进程同时调用
     * 为了防止 id 是取负值，需要将返回结果与 0x7FFFFFFF 做按位与操作，即 0111 FFF FFFF
     * 因此 id 取值范围是 [ 0, 2^31 - 1 ]，当 id 达到最大值，会重新从 0 开始自增
     *
     * @return 下一个自增的 id
     */
    public static int nextCallId() {
        return callIdCounter.getAndIncrement() & 0x7FFFFFFF;
    }


    /**
     * 调用 RPC 服务端，相关信息定义在 <code>remoteId</code>
     *
     * @param rpcKind - rpc 类型
     * @param rpcRequest -  客户端调用请求，包含序列化方法和参数等信息
     * @param remoteId - rpc server
     * @returns rpc 返回值
     * 抛网络异常或者远程代码执行异常
     */
    public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest, ConnectionId remoteId)
            throws IOException {
        return call(rpcKind, rpcRequest, remoteId, RPC.RPC_SERVICE_CLASS_DEFAULT);
    }

    /**
     * 调用 RPC 服务端，相关信息定义在 <code>remoteId</code>
     * @param rpcKind - rpc 类型
     * @param rpcRequest - 包含序列化方法和参数
     * @param remoteId - rpc server
     * @param serviceClass service class for rpc，后续拓展使用，目前无用
     * @return rpc 返回值
     * @throws IOException 抛网络异常或者远程代码执行异常
     */
    public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
                         ConnectionId remoteId, int serviceClass)
            throws IOException {
        return null;
    }

    /**
     * 代表网络连接的 Connection 类
     * 负责：建立网络连接，发送网络请求，等待返回结果
     */
    private class Connection extends Thread {
        private final ConnectionId remoteId;

        /**
         * 服务器地址：ip + port
         */
        private InetSocketAddress server;

        private Socket socket = null;
        private DataInputStream in;
        private DataOutputStream out;

        private final int rpcTimeOut;
        /** 连接空闲时最大休眠时间，单位：毫秒 */
        private final int maxIdleTime;
        /** 如果 true 表示禁用 Nagle 算法
         *  Negale算法是指发送方发送数据的时候不会立刻发出，而是先放在缓冲区内，等待缓冲区满了再发送
         */
        private final boolean tcpNoDelay;
        /** 是否需要发送ping message */
        private final boolean doPing;
        /** 发送 ping message 的时间间隔， 单位：毫秒 */
        private int pingInterval;
        /** socket 连接超时最大重试次数 */
        private final int maxRetriesOnSocketTimeouts;
        private int serviceClass;

        /** 标识是否应该关闭连接，默认值： false */
        private AtomicBoolean shouldCloseConnection = new AtomicBoolean();
        /** I/O 活动的最新时间 */
        private AtomicLong lastActivity = new AtomicLong();

        private final Object sendRpcRequestLock = new Object();

        private Hashtable<Integer, Call> calls = new Hashtable<>();

        public Connection(ConnectionId remoteId, Integer serviceClass) throws IOException {
            this.remoteId = remoteId;
            this.server = remoteId.getAddress();
            if (server.isUnresolved()) {
                throw new UnknownHostException("Unknown host name : " + server.toString());
            }
            this.rpcTimeOut = remoteId.getRpcTimeOut();
            this.maxRetriesOnSocketTimeouts = remoteId.getMaxRetriesOnSocketTimeouts();
            this.maxIdleTime = remoteId.getMaxIdleTime();
            this.tcpNoDelay = remoteId.isTcpNoDelay();
            this.doPing = remoteId.isDoPing();
            if (doPing) {
                // todo ping message
                // 构造 RPC header with the callId as the ping callId
            }
            this.pingInterval = remoteId.getPingInterval();
            this.serviceClass = serviceClass;
            if (LOG.isDebugEnabled()) {
                LOG.debug("The ping interval is " + this.pingInterval + " ms.");
            }

            this.setName("IPC Client (" + socketFactory.hashCode() +") connection to " +
                    server.toString());
            this.setDaemon(true);
        }

        /**
         * 将当前时间更新为 I/O 最新活动时间
         */
        private void touch() {
            lastActivity.set(System.currentTimeMillis());
        }


        public InetSocketAddress getServer() {
            return server;
        }

        /**
         * 向该 Connection 对象的 call 队列加入一个 call
         * 同时唤醒等待的线程
         * @param call 加入的队列得元素
         * @return 如果连接处于关闭状态是添加 call，返回 false。正确加入队列返回 true
         */
        private synchronized boolean addCall(Call call) {
            if (shouldCloseConnection.get()) {
                return false;
            }
            calls.put(call.id, call);
            //todo 更新run方法时再添加通知
            return true;
        }

        private synchronized void setupConnection() throws IOException {
            short timeOutFailures = 0;
            while (true) {
                try {
                    this.socket = socketFactory.createSocket();
                    // 立刻发送数据，Client 本次不采用 Negale算法
                    this.socket.setTcpNoDelay(tcpNoDelay);
                    //  表示底层的TCP 实现会监视该连接是否有效
                    this.socket.setKeepAlive(true);

                    NetUtils.connect(socket, server, connectionTimeOut);

                    if (rpcTimeOut > 0) {
                        // 用 rpcTimeOut 覆盖 pingInterval
                        pingInterval = rpcTimeOut;
                    }
                    socket.setSoTimeout(pingInterval);
                    return;
                } catch (SocketTimeoutException ste) {
                    handleConnectionTimeout(timeOutFailures++, maxRetriesOnSocketTimeouts, ste);
                } catch (IOException ioe) {
                    throw ioe;
                }
            }
        }

        /**
         * 建立连接后发送的连接头（header）
         * +----------------------------------+
         * |  "mrpc" 4 bytes                  |
         * +----------------------------------+
         * |  Version (1 byte)                |
         * +----------------------------------+
         * |  Service Class (1 byte)          |
         * +----------------------------------+
         * |  AuthProtocol (1 byte)           |
         * +----------------------------------+
         * @param outStream 输出流
         * @throws IOException
         */
        private void writeConnectionHeader(OutputStream outStream) throws IOException {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(outStream));

            out.write(RpcConstants.HEADER.array());
            out.write(RpcConstants.CURRENT_VERSION);
            out.write(serviceClass);
            // 暂无授权协议，写 0
            out.write(0);
            out.flush();
        }

        /**
         * 每次连接都要写连接上下文（context）
         * @param remoteId
         */
        private void writeConnectionContext(ConnectionId remoteId) throws IOException {
            IpcConnectionContextProtos.IpcConnectionContextProto connectionContext =
                    ProtoUtil.makeIpcConnectionContext(
                            RPC.getProtocolName(remoteId.getProtocol()));

            RpcRequestHeaderProto connectionContextHeader = ProtoUtil
                    .makeRpcRequestHeader(RPC.RpcKind.RPC_PROTOCOL_BUFFER,
                            RpcRequestHeaderProto.OperationProto.RPC_FINAL_PACKET,
                            RpcConstants.CONNECTION_CONTEXT_CALL_ID, RpcConstants.INVALID_RETRY_COUNT,
                            clientId);

            ProtobufRpcEngine.RpcRequestMessageWrapper request =
                    new ProtobufRpcEngine.RpcRequestMessageWrapper(connectionContextHeader, connectionContext);

            out.writeInt(request.getLength());
            request.write(out);
        }


        /**
         * 连接 server，建立 IO 流。向 server 发送 header 信息
         * 启动线程，等待返回信息。由于多个线程持有相同 Connection 对象，需要保证只有一个线程可以调用 start 方法
         * 因此该方法需要用 synchronized 修饰
         */
        private synchronized void setupIOStreams() {
            if (socket != null || shouldCloseConnection.get()) {
                return;
            }

            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Connecting to " + server);
                }
                setupConnection();
                InputStream inStream = NetUtils.getInputStream(socket);
                OutputStream outStream = NetUtils.getOutputStream(socket);
                writeConnectionHeader(outStream);

                if (doPing) {
                    /**
                     * todo ping相关
                     */
                }

                /**
                 * DataInputStream 可以支持 Java 原子类型的输入输入
                 * BufferedInputStream 具有缓冲的作用
                 */
                this.in = new DataInputStream(new BufferedInputStream(inStream));
                this.out = new DataOutputStream(new BufferedOutputStream(outStream));

                writeConnectionContext(remoteId);

                touch();

                // 启动 receiver 线程，用来接收响应信息
                start();
                return;
            } catch (Throwable t) {
                if (t instanceof IOException) {
                    markClosed((IOException) t);
                } else {
                    markClosed(new IOException("Couldn't set up IO streams", t));
                }
                close();
            }
        }

        private void closeConnection() {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
                LOG.warn("Not able to close a socket", e);
            }
            //将 socket 置位 null 为了下次能够重新建立连接
            socket = null;
        }

        private void handleConnectionTimeout(
                int curRetries, int maxRetries, IOException ioe) throws IOException {

            closeConnection();
            /**
             * 达到重试的最大次数，将异常抛出
             */
            if (curRetries >= maxRetries) {
                throw ioe;
            }
            LOG.info("Retrying connect to server: " + server + ". Already tried "
                    + curRetries + " time(s); maxRetries=" + maxRetries);
        }

        @Override
        public void run() {
        }

        /**
         * 向服务端发送 rpc 请求
         * @param call 包含 rpc 调用相关的信息
         */
        public void sendRpcRequest(final Call call)
                throws IOException, InterruptedException {
            if (shouldCloseConnection.get()) {
                return;
            }

            /**
             * 序列化需要被发送出去的信息，这里由实际调用方法的线程来完成
             * 实际发送前各个线程可以并行地准备（序列化）待发送的信息，而不是发送线程（sendParamsExecutor）
             * 这样做的好处一方面减少锁的粒度，另一方面序列化过程中抛异常每个线程可以单独、独立地报告
             *
             * 发送的格式:
             * 0) 下面 1、2 两项的长度之和，4字节
             * 1) RpcRequestHeader
             * 2) RpcRequest
             * 1、2两项在下面代码序列化
             */
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final DataOutputStream tmpOut = new DataOutputStream(bo);
            // 暂时没有重试机制，因此参数 retryCount=-1
            RpcRequestHeaderProto header = ProtoUtil.makeRpcRequestHeader(
                    call.rpcKind, RpcRequestHeaderProto.OperationProto.RPC_FINAL_PACKET,
                    call.id, -1, clientId);
            header.writeDelimitedTo(tmpOut);
            call.rpcRequest.write(tmpOut);

            synchronized (sendRpcRequestLock) {
                Future<?> senderFuture = sendParamsExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        //多线程并发调用服务端，需要锁住发送流 out
                        try {
                            synchronized (Connection.this.out) {
                                if (shouldCloseConnection.get()) {
                                    return;
                                }
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(getName() + " sending #" + call.id);
                                }

                                byte[] data = bo.toByteArray();
                                int dataLen = bo.size();
                                out.writeInt(dataLen);
                                out.write(data, 0, dataLen);
                                out.flush();
                            }
                        } catch (IOException e) {
                            /**
                             * 如果在这里发生异常，将处于不可恢复状态
                             * 因此，关闭连接，终止所有未完成的调用
                             */
                            markClosed(e);
                        } finally {
                            IOUtils.closeStream(tmpOut);
                        }
                    }
                });

                try {
                    senderFuture.get();
                } catch (ExecutionException e) {
                    // Java 有异常链，该异常可能是另一个异常引起的
                    // 调用 getCause 方法获得真正的异常
                    Throwable cause = e.getCause();

                    /**
                     * 这里只能是运行时异常，因为 IOException 异常以及在上面的匿名内部类捕获了
                     */
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    } else {
                        throw new RuntimeException("unexpected checked exception", cause);
                    }
                }
            }
        }

        private synchronized void markClosed(IOException e) {
        }

        /**
         * 关闭连接
         */
        private synchronized void close() {

        }
    }

    /**
     * 从缓冲池中获取一个 Connection 对象，如果池中不存在，需要创建对象并放入缓冲池
     *
     * @return Connection 对象
     */
    private Connection getConnection(ConnectionId remoteId,
                                     Call call, int serviceClass) throws IOException {
        if (!running.get()) {
            throw new IOException("The client is stopped.");
        }
        Connection connection;
        do {
            synchronized (connections) {
                connection = connections.get(remoteId);
                if (connection == null) {
                    connection = new Connection(remoteId, serviceClass);
                    connections.put(remoteId, connection);
                }
            }
        } while (!connection.addCall(call));

        //我们没有在上面 synchronized (connections) 代码块调用该方法
        //原因是如果服务端慢，建立连接会花费很长时间，会拖慢整个系统
        connection.setupIOStreams();
        return connection;
    }

    /**
     *  网络连接标识（用于判断能否复用同一个网络连接）
     *  该类用来存储与连接相关的 address、protocol 等信息，
     */
    public static class ConnectionId {
        final InetSocketAddress address;
        private static final int PRIME = 16777619;
        private final Class<?> protocol;
        private final int rpcTimeOut;
        /** 连接空闲时最大休眠时间，单位：毫秒 */
        private final int maxIdleTime;
        /** 如果 true 表示禁用 Nagle 算法 */
        private final boolean tcpNoDelay;
        /** 是否需要发送ping message */
        private final boolean doPing;
        /** 发送 ping message 的时间间隔， 单位：毫秒 */
        private final int pingInterval;
        /** socket 连接超时最大重试次数 */
        private final int maxRetriesOnSocketTimeouts;
        private final Configuration conf;

        public ConnectionId(InetSocketAddress address,
                            Class<?> protocol,
                            int rpcTimeOut,
                            Configuration conf) {
            this.address = address;
            this.protocol = protocol;
            this.rpcTimeOut = rpcTimeOut;

            this.maxIdleTime = conf.getInt(
                    CommonConfigurationKeysPublic.IPC_CLIENT_CONNECTION_MAXIDLETIME_KEY,
                    CommonConfigurationKeysPublic.IPC_CLIENT_CONNECTION_MAXIDLETIME_DEFAULT);
            this.maxRetriesOnSocketTimeouts = conf.getInt(
                    CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SOCKET_TIMEOUTS_KEY,
                    CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SOCKET_TIMEOUTS_DEFAULT);
            this.tcpNoDelay = conf.getBoolean(
                    CommonConfigurationKeysPublic.IPC_CLIENT_TCPNODELAY_KEY,
                    CommonConfigurationKeysPublic.IPC_CLIENT_TCPNODELAY_DEFAULT);
            this.doPing = conf.getBoolean(
                    CommonConfigurationKeysPublic.IPC_CLIENT_PING_KEY,
                    CommonConfigurationKeysPublic.IPC_CLIENT_PING_DEFAULT);
            this.pingInterval = doPing ?
                    conf.getInt(
                            CommonConfigurationKeysPublic.IPC_PING_INTERVAL_KEY,
                            CommonConfigurationKeysPublic.IPC_PING_INTERVAL_DEFAULT)
                    : 0;
            this.conf = conf;
        }

        public InetSocketAddress getAddress() {
            return address;
        }

        public Class<?> getProtocol() {
            return protocol;
        }

        public int getRpcTimeOut() {
            return rpcTimeOut;
        }

        public int getMaxIdleTime() {
            return maxIdleTime;
        }

        public boolean isTcpNoDelay() {
            return tcpNoDelay;
        }

        public boolean isDoPing() {
            return doPing;
        }

        public int getPingInterval() {
            return pingInterval;
        }

        public int getMaxRetriesOnSocketTimeouts() {
            return maxRetriesOnSocketTimeouts;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ConnectionId) {
                ConnectionId that = (ConnectionId) obj;
                return Objects.equals(this.address, that.address)
                        && Objects.equals(this.protocol, that.protocol)
                        && this.rpcTimeOut == that.rpcTimeOut
                        && this.maxIdleTime == that.maxIdleTime
                        && this.tcpNoDelay == that.tcpNoDelay
                        && this.doPing == that.doPing
                        && this.pingInterval == that.pingInterval;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = ((address == null) ? 0 : address.hashCode());
            result = PRIME * result + (doPing ? 1231 : 1237);
            result = PRIME * result + maxIdleTime;
            result = PRIME * result + pingInterval;
            result = PRIME * result + ((protocol == null) ? 0 : protocol.hashCode());
            result = PRIME * result + rpcTimeOut;
            result = PRIME * result + (tcpNoDelay ? 1231 : 1237);
            return result;
        }

        @Override
        public String toString() {
            return address.toString();
        }

    }
}
