package com.rchen102;

import com.rchen102.conf.Configuration;
import com.rchen102.protocol.ClientProtocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

public class ManisClient implements Closeable {

    volatile boolean clientRunning = true;
    final ClientProtocol manisDb;

    public ManisClient(URI manisDbUri, Configuration conf) throws IOException {
        ManisDbProxies.ProxyInfo<ClientProtocol> proxyInfo = null;
        proxyInfo = ManisDbProxies.createProxy(conf, manisDbUri, ClientProtocol.class);
        this.manisDb = proxyInfo.getProxy();
    }

    public int getTableCount(String dbName, String tbName) throws IOException{
        return this.manisDb.getTableCount(dbName, tbName);
    }

    @Override
    public void close() throws IOException {
        //TODO close db connection
    }
}
