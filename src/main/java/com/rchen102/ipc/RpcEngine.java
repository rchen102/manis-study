package com.rchen102.ipc;

import com.rchen102.conf.Configuration;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;

public interface RpcEngine {
    /**
     * 获取用于客户端使用的代理对象
     * @param protocol 将要代理的接口
     * @param clientVersion 客户端版本
     * @param address 服务端地址
     * @param conf
     * @param factory 创建Socket的工厂对象
     * @param rpcTimeOut rpc超时时间
     * @param <T>
     * @return 接口的代理对象
     * @throws IOException
     */
    <T> T getProxy(Class<T> protocol,
                   long clientVersion,
                   InetSocketAddress address,
                   Configuration conf,
                   SocketFactory factory,
                   int rpcTimeOut) throws IOException;
}
