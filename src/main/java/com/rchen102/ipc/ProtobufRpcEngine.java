package com.rchen102.ipc;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Message;
import com.google.protobuf.ServiceException;
import com.rchen102.conf.Configuration;
import com.rchen102.io.DataOutputOutputStream;
import com.rchen102.io.Writable;
import com.rchen102.ipc.protobuf.ProtobufRpcEngineProtos;
import com.rchen102.ipc.protobuf.RpcHeaderProtos;
import com.rchen102.util.ProtoUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.net.SocketFactory;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

/**
 * 为 ManagerProtocol 接口提供代理（创建代理）
 */
public class ProtobufRpcEngine implements RpcEngine {
    public static final Log LOG = LogFactory.getLog(ProtobufRpcEngine.class);

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> protocol, long clientVersion, InetSocketAddress address, Configuration conf, SocketFactory factory, int rpcTimeOut) throws IOException {
        Invoker invoker = new Invoker(protocol, address, conf, factory, rpcTimeOut);
        return (T) Proxy.newProxyInstance(protocol.getClassLoader(), new Class[]{protocol}, invoker);
    }

    private static class Invoker implements RpcInvocationHandler{
        private Client client;
        private Client.ConnectionId remoteId;
        private final String protocolName;
        private final long clientProtocolVersion;
        private final int NORMAL_ARGS_LEN = 2;

        private Invoker(Class<?> protocol, InetSocketAddress address,
                        Configuration conf, SocketFactory factory,
                        int rpcTimeOut) {
            this.protocolName = RPC.getProtocolName(protocol);
            this.clientProtocolVersion = RPC.getProtocolVersion(protocol);
            this.remoteId = new Client.ConnectionId(address, protocol, rpcTimeOut, conf);
            this.client = new Client(RpcResponseWrapper.class, conf, factory);
        }

        private ProtobufRpcEngineProtos.RequestHeaderProto constructRpcRequesHeader(Method method) {
            ProtobufRpcEngineProtos.RequestHeaderProto.Builder headerBuilder = ProtobufRpcEngineProtos.RequestHeaderProto
                    .newBuilder();
            headerBuilder.setMethodName(method.getName());
            headerBuilder.setDeclaringClassProtocolName(protocolName);
            headerBuilder.setClientProtocolVersion(clientProtocolVersion);
            return headerBuilder.build();
        }

        private Message getReturnType(Method method) throws Exception {
            Class<?> returnType = method.getReturnType();
            Method newInstMethod = returnType.getMethod("getDefaultInstance");
            newInstMethod.setAccessible(true);
            return (Message) newInstMethod.invoke(null, (Object []) null);
        }

        /**
         * RPC 在客户端的 invoker
         * 上层希望仅有 ServiceException 异常被抛出，因此该方法仅抛出 ServiceException 异常
         *
         * 以下两种情况都构造 ServiceException :
         * <ol>
         * <li>该方法中客户端抛出的异常</li>
         * <li>服务端的异常包装在 RemoteException 中的异常</li>
         * </ol>
         *
         * @param proxy proxy
         * @param method 调用的方法
         * @param args 参数
         * @return 返回值
         * @throws ServiceException 异常
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            long startTime = 0;
            if (LOG.isDebugEnabled()) {
                startTime = System.currentTimeMillis();
            }

            if (args.length != NORMAL_ARGS_LEN) {
                throw new ServiceException("Too many parameters for request. Method: ["
                        + method.getName() + "]" + ", Expected: 2, Actual: "
                        + args.length);
            }
            if (args[1] == null) {
                throw new ServiceException("null param while calling Method: ["
                        + method.getName() + "]");
            }

            ProtobufRpcEngineProtos.RequestHeaderProto header = constructRpcRequesHeader(method);
            Message theRequest = (Message) args[1];
            final RpcResponseWrapper res;
            try {
                res = (RpcResponseWrapper) client.call(RPC.RpcKind.RPC_PROTOCOL_BUFFER,
                        new RpcRequestWrapper(header, theRequest), remoteId);
            } catch (Throwable e) {
                throw new ServiceException(e);
            }

            if (LOG.isDebugEnabled()) {
                long callTime = System.currentTimeMillis() - startTime;
                LOG.debug("Call: " + method.getName() + " took " + callTime + "ms");
            }

            Message prototype = null;
            try {
                prototype = getReturnType(method);
            } catch (Exception e) {
                throw new ServiceException(e);
            }
            Message returnMessage = null;
            try {
                returnMessage = prototype.newBuilderForType()
                        .mergeFrom(res.theResponseRead)
                        .build();
            } catch (Throwable t) {
                throw new ServiceException(t);
            }

            return returnMessage;
        }

        @Override
        public void close() throws IOException {
            client.stop();
        }
    }

    interface RpcWrapper extends Writable {
        int getLength();
    }

    private static abstract class BaseRpcMessageWithHeader<T extends GeneratedMessage>
            implements RpcWrapper {
        /**
         * 封装公共信息，客户端版本，本次请求的方法名，协议名称等
         */
        T requestHeader;

        /**
         * 具体的调用参数,类型为 Message 接口
         * 用于客户端存储 请求
         */
        Message theRequest;

        /**
         * theRequest 序列化后的字节数组
         * 用于服务端 读取请求
         */
        byte[] theRequestRead;

        public BaseRpcMessageWithHeader() {}

        public BaseRpcMessageWithHeader(T requestHeader, Message theRequest) {
            this.requestHeader = requestHeader;
            this.theRequest = theRequest;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            OutputStream os = DataOutputOutputStream.constructDataOutputStream(out);
            requestHeader.writeDelimitedTo(os);
            theRequest.writeDelimitedTo(os);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            requestHeader = parseHeaderFrom(readVarIntBytes(in));
            theRequestRead = readMessageRequest(in);
        }

        /**
         * 子类会覆盖该方法
         * @param in
         * @return
         */
        byte[] readMessageRequest(DataInput in) throws IOException {
            return readVarIntBytes(in);
        }

        private byte[] readVarIntBytes(DataInput in) throws IOException {
            int length = ProtoUtil.readRawVarInt32(in);
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return bytes;
        }

        /**
         * 子类重写该方法
         * @param bytes 序列化字节数据
         * @return Header类型
         * @throws IOException
         */
        abstract T parseHeaderFrom(byte[] bytes) throws IOException;

        /**
         * 包括两部分
         * 1. header 序列化后的长度及长度本身的 varInt32 编码后的长度
         * 2. request 序列化后的长度以及长度本身 varInt32 编码后的长度
         * @return 序列化后的数据长度
         */
        @Override
        public int getLength() {
            int headerLen = requestHeader.getSerializedSize();
            int requestLen;
            if (theRequest != null) {
                requestLen = theRequest.getSerializedSize();
            } else if (theRequestRead != null) {
                requestLen = theRequestRead.length;
            } else {
                throw new IllegalArgumentException("getLength on uninitialized RpcWrapper");
            }
            return CodedOutputStream.computeRawVarint32Size(headerLen) + headerLen +
                    CodedOutputStream.computeRawVarint32Size(requestLen) + requestLen;
        }
    }

    private static class RpcRequestWrapper extends
            BaseRpcMessageWithHeader<ProtobufRpcEngineProtos.RequestHeaderProto> {

        @SuppressWarnings("unused")
        public RpcRequestWrapper() {}

        public RpcRequestWrapper(ProtobufRpcEngineProtos.RequestHeaderProto requestHeader, Message theRequest) {
            super(requestHeader, theRequest);
        }

        @Override
        ProtobufRpcEngineProtos.RequestHeaderProto parseHeaderFrom(byte[] bytes) throws IOException {
            return ProtobufRpcEngineProtos.RequestHeaderProto.parseFrom(bytes);
        }

        @Override
        public String toString() {
            return requestHeader.getDeclaringClassProtocolName() + "." +
                    requestHeader.getMethodName();
        }
    }

    public static class RpcRequestMessageWrapper extends
            BaseRpcMessageWithHeader<RpcHeaderProtos.RpcRequestHeaderProto> {

        public RpcRequestMessageWrapper(RpcHeaderProtos.RpcRequestHeaderProto requestHeader, Message theRequest) {
            super(requestHeader, theRequest);
        }

        @Override
        RpcHeaderProtos.RpcRequestHeaderProto parseHeaderFrom(byte[] bytes) throws IOException {
            return RpcHeaderProtos.RpcRequestHeaderProto.parseFrom(bytes);
        }
    }

    /**
     * Protocol Buffer 响应信息的 wrapper
     *
     */
    public static class RpcResponseWrapper implements RpcWrapper {
        Message theRespone;
        byte[] theResponseRead;

        public RpcResponseWrapper() {
        }

        public RpcResponseWrapper(Message theRespone) {
            this.theRespone = theRespone;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            OutputStream os = DataOutputOutputStream.constructDataOutputStream(out);

            theRespone.writeDelimitedTo(os);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            int len = ProtoUtil.readRawVarInt32(in);
            theResponseRead = new byte[len];
            in.readFully(theResponseRead);
        }

        @Override
        public int getLength() {
            int resLen = 0;
            if (theRespone != null) {
                resLen = theRespone.getSerializedSize();
            } else if (theResponseRead != null) {
                resLen = theResponseRead.length;
            } else {
                throw new IllegalArgumentException(
                        "getLength on uninitialized RpcWrapper");
            }
            return CodedOutputStream.computeRawVarint32Size(resLen) + resLen;
        }
    }
}
