package com.rchen102.protocolPB;

import com.google.protobuf.ServiceException;
import com.rchen102.protocol.ClientProtocol;
import com.rchen102.protocol.proto.ClientManisDbProtocolProtos;

import java.io.Closeable;
import java.io.IOException;

public class ClientManisDbProtocolTranslatorPB implements
        ClientProtocol, Closeable {
    private ClientManisDbProtocolPB rpcProxy;

    public ClientManisDbProtocolTranslatorPB(ClientManisDbProtocolPB proxy) {
        rpcProxy = proxy;
    }

    @Override
    public int getTableCount(String dbName, String tbName) throws IOException {
        ClientManisDbProtocolProtos.GetTableCountRequestProto request = ClientManisDbProtocolProtos.GetTableCountRequestProto.newBuilder()
                .setDbName(dbName)
                .setTbName(tbName)
                .build();
        try {
            return rpcProxy.getTableCount(null, request).getResult();
        } catch (ServiceException e) {
            throw new IOException(e);
        }
    }
    @Override
    public void close() throws IOException {
    }
}
