package com.rchen102.ipc;

import com.rchen102.conf.Configuration;

import javax.net.SocketFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

/**
 * 为 ManagerProtocol 接口提供代理（创建代理）
 */
public class ProtobufRpcEngine implements RpcEngine {

    private static class Invoker implements RpcInvocationHandler{
        private Invoker(Class<?> protocol, InetSocketAddress address,
                        Configuration conf, SocketFactory factory,
                        int rpcTimeout) {

        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return null;
        }

        @Override
        public void close() throws IOException {

        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> protocol, long clientVersion, InetSocketAddress address, Configuration conf, SocketFactory factory, int rpcTimeOut) throws IOException {
        Invoker invoker = new Invoker(protocol, address, conf, factory, rpcTimeOut);
        return (T) Proxy.newProxyInstance(protocol.getClassLoader(), new Class[]{protocol}, invoker);
    }
}
