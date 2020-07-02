package com.rchen102.ipc;

import com.rchen102.conf.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.net.SocketFactory;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 公共的工具类，方便客户端获取代理
 */
public class RPC {
    static final Log LOG = LogFactory.getLog(RPC.class);
    final static int RPC_SERVICE_CLASS_DEFAULT = 0;

    /**
     * RPC 类型枚举
     */
    public enum RpcKind {
        /**
         * RPC_SERIALIZABLE: SerializableRpcEngine
         * RPC_PROTOCOL_BUFFER: ProtoBufRpcEngine
         */
        RPC_BUILTIN ((short) 1),
        RPC_SERIALIZABLE ((short) 2),
        RPC_PROTOCOL_BUFFER ((short) 3);

        final static int MAX_INDEX = RPC_PROTOCOL_BUFFER.value;
        public final short value;


        RpcKind(short value) {
            this.value = value;
        }
    }

    /**
     * 存储 接口（协议）与 RPC引擎的映射
     */
    public static final String RPC_ENGINE = "rpc.engine";
    private static final Map<Class<?>, RpcEngine> PROTOCOL_ENGINS
            = new HashMap<>();

    /**
     * 配置 config 信息，为协议（接口）设置好对应的Rpc引擎
     */
    public static void setProtocolEngine(Configuration conf,
                                         Class<?> protocol, Class<?> engine) {
        conf.setClass(RPC_ENGINE + "." + protocol.getName(), engine, RpcEngine.class);
    }

    /**
     * 根据配置信息，返回该协议对应的Rpc引擎
     */
    public static synchronized <T> RpcEngine getProtocolEngine(Class<T> protocol, Configuration conf) {
        RpcEngine engine = PROTOCOL_ENGINS.get(protocol);
        if (engine == null) {
            Class<?> clazz = conf.getClass(RPC_ENGINE + "." + protocol.getName(), SerializableRpcEngine.class);

            try {
                // 通过反射实例化RpcEngine的实现类
                Constructor constructor = clazz.getDeclaredConstructor();
                engine = (RpcEngine)constructor.newInstance();
                PROTOCOL_ENGINS.put(protocol, engine);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return engine;
    }

    public static <T> T getProtocolProxy(Class<T> protocol,
                                         long clientVersion,
                                         InetSocketAddress address,
                                         Configuration conf,
                                         SocketFactory factory,
                                         int rpcTimeOut)
            throws IOException {
        return getProtocolEngine(protocol, conf).getProxy(protocol, clientVersion,
                address, conf, factory, rpcTimeOut);
    }

    /**
     * 获取协议的版本号
     * @param protocol
     * @return
     */
    public static long getProtocolVersion(Class<?> protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("Null protocol");
        }
        long version;
        ProtocolInfo anno = protocol.getAnnotation(ProtocolInfo.class);
        if (anno != null) {
            version = anno.protocolVersion();
            if (version != -1) {
                return version;
            }
        }
        try {
            Field versionField = protocol.getField("versionID");
            versionField.setAccessible(true);
            return versionField.getLong(protocol);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getProtocolName(Class<?> protocol) {
        if (protocol == null) {
            return null;
        }
        ProtocolInfo anno = protocol.getAnnotation(ProtocolInfo.class);
        return anno == null ? protocol.getName() : anno.protocolName();
    }

    /**
     * 停止代理，该代理需要实现 {@link Closeable} 或者关联 {@link RpcInvocationHandler}
     * @param proxy 需要停止的代理
     *
     * @throws IllegalArgumentException
     *      代理没有实现 {@link Closeable} 接口
     */
    public static void stopProxy(Object proxy) {
        if (proxy == null) {
            throw new IllegalArgumentException("Cannot close proxy since it is null");
        }

        try {
            if (proxy instanceof Closeable) {
                ((Closeable) proxy).close();
                return;
            }

            InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (handler instanceof Closeable) {
                ((Closeable) handler).close();
                return;
            }
        } catch (IOException e) {
            LOG.error("Closing proxy or invocation handler caused exception", e);
        } catch (IllegalArgumentException e) {
            LOG.error("RPC.stopProxy called on non proxy: class=" + proxy.getClass().getName(), e);
        }

        // proxy 没有 close 方法
        throw new IllegalArgumentException(
                "Cannot close proxy - is not Closeable or "
                        + "does not provide closeable invocation handler "
                        + proxy.getClass());
    }
}
