package com.rchen102;

import com.rchen102.conf.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * Tool class to acquire a proxy
 */
public class ManisDbProxies {

    /**
     * Static class to encapsulate proxy object
     * @param <PROXYTYPE> using generic, since more than one interface need proxy,
     */
    public static class ProxyInfo<PROXYTYPE> {
        private final PROXYTYPE proxy;
        private final InetSocketAddress address;

        public ProxyInfo(PROXYTYPE proxy, InetSocketAddress address) {
            this.proxy = proxy;
            this.address = address;
        }

        public PROXYTYPE getProxy() {
            return proxy;
        }

        public InetSocketAddress getAddress() {
            return address;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> ProxyInfo<T> createProxy(
            Configuration conf, URI uri, Class<T> xface) throws IOException {
        return null;
    }
}
