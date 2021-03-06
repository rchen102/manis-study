package com.rchen102.util;

import com.google.protobuf.ByteString;
import com.rchen102.ipc.RPC;
import com.rchen102.ipc.protobuf.IpcConnectionContextProtos;
import com.rchen102.ipc.protobuf.RpcHeaderProtos;

import java.io.DataInput;
import java.io.IOException;

/**
 * 提供 Protocol Buffer 相关的工具方法
 * @author duma
 */
public class ProtoUtil {
    /**
     * 从输入流中读取变长的 int 类型变量
     * Protocol Buffer writeDelimitedTo 写入的长度使用该编码方式
     * @param in 输入流
     * @return 解码结果
     * @throws IOException 编码格式错误或者EOF.
     */
    public static int readRawVarInt32(DataInput in) throws IOException {
        byte tmp = in.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        int result = tmp & 0x7f;
        if ((tmp = in.readByte()) >= 0) {
            result |= tmp << 7;
        } else {
            result |= (tmp & 0x7f) << 7;
            if ((tmp = in.readByte()) >= 0) {
                result |= tmp << 14;
            } else {
                result |= (tmp & 0x7f) << 14;
                if ((tmp = in.readByte()) >= 0) {
                    result |= tmp << 21;
                } else {
                    result |= (tmp & 0x7f) << 21;
                    result |= (tmp = in.readByte()) << 28;
                    if (tmp < 0) {
                        // varInt32 最多使用 5 位编码，最后一位仍然小于 0， 说明有问题
                        for (int i = 0; i < 5; i++) {
                            if (in.readByte() >= 0) {
                                return result;
                            }
                        }
                        throw new IOException("Malformed varint");
                    }
                }
            }
        }
        return result;
    }

    public static IpcConnectionContextProtos.IpcConnectionContextProto makeIpcConnectionContext(
            final String protocolName) {
        IpcConnectionContextProtos.IpcConnectionContextProto.Builder retBuilder = IpcConnectionContextProtos.IpcConnectionContextProto.newBuilder();
        retBuilder.setProtocol(protocolName);
        /**
         * 这里可以发送安全、授权以及用户信息相关的信息给服务端
         * 为了简化，manis 中没有设置相关的信息
         */
        return retBuilder.build();
    }

    static RpcHeaderProtos.RpcKindProto convertRpcKind(RPC.RpcKind rpcKind) {
        switch (rpcKind) {
            case RPC_BUILTIN: return RpcHeaderProtos.RpcKindProto.RPC_BUILTIN;
            case RPC_SERIALIZABLE: return RpcHeaderProtos.RpcKindProto.RPC_SERIALIZABLE;
            case RPC_PROTOCOL_BUFFER: return RpcHeaderProtos.RpcKindProto.RPC_PROTOCOL_BUFFER;
            default: return null;
        }
    }

    public static RPC.RpcKind convertRpcKind(RpcHeaderProtos.RpcKindProto rpcKind) {
        switch (rpcKind) {
            case RPC_BUILTIN: return RPC.RpcKind.RPC_BUILTIN;
            case RPC_SERIALIZABLE: return RPC.RpcKind.RPC_SERIALIZABLE;
            case RPC_PROTOCOL_BUFFER: return RPC.RpcKind.RPC_PROTOCOL_BUFFER;
            default: return null;
        }
    }

    public static RpcHeaderProtos.RpcRequestHeaderProto makeRpcRequestHeader(RPC.RpcKind rpcKind,
                                                                             RpcHeaderProtos.RpcRequestHeaderProto.OperationProto operation,
                                                                             int callId, int retryCount, byte[] clientId) {
        RpcHeaderProtos.RpcRequestHeaderProto.Builder retBuilder = RpcHeaderProtos.RpcRequestHeaderProto.newBuilder();

        retBuilder.setRpcKind(convertRpcKind(rpcKind)).setRpcOp(operation)
                .setCallId(callId).setRetryCount(retryCount)
                .setClientId(ByteString.copyFrom(clientId));

        return retBuilder.build();
    }
}
