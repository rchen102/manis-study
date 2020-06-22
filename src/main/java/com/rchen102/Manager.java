package com.rchen102;

import com.rchen102.conf.Configuration;
import com.rchen102.ipc.RPC;
import com.rchen102.protocol.ManagerProtocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

public class Manager implements Closeable {

    volatile boolean clientRunning = true;
    final ManagerProtocol manisDb;

    public Manager(URI manisDbUri, Configuration conf) throws IOException {
        ManisDbProxies.ProxyInfo<ManagerProtocol> proxyInfo = null;
        proxyInfo = ManisDbProxies.createProxy(conf, manisDbUri, ManagerProtocol.class);
        this.manisDb = proxyInfo.getProxy();
    }

    public boolean setMaxTable(int tableNum) {
        return this.manisDb.setMaxTable(tableNum);
    }

    private void closeConnectionToManisDb() {
        RPC.stopProxy(manisDb);
    }

    @Override
    public synchronized void close() throws IOException {
        if (clientRunning) {
            clientRunning = false;
            closeConnectionToManisDb();
        }
    }
}
