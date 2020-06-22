package com.rchen102.ipc;

import com.rchen102.conf.Configuration;
import com.rchen102.io.Writable;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;

public class Client {
    /**
     *
     * @param valueClass 调用的返回类型
     * @param conf 配置对象
     * @param factory socket 工厂
     */
    public Client(Class<? extends Writable> valueClass, Configuration conf,
                  SocketFactory factory) {
    }

    public void stop() {

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
     * @param serviceClass service class for rpc
     * @return rpc 返回值
     * @throws IOException 抛网络异常或者远程代码执行异常
     */
    public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
                         ConnectionId remoteId, int serviceClass)
            throws IOException {
        return null;
    }

    /**
     *  网络连接标识（用于判断能否复用同一个网络连接）
     *  该类用来存储与连接相关的 address、protocol 等信息，
     */
    public static class ConnectionId {

        public ConnectionId(InetSocketAddress address,
                            Class<?> protocol,
                            int rpcTimeOut,
                            Configuration conf) {
        }
    }
}
