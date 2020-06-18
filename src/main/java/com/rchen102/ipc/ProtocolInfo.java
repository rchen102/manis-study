package com.rchen102.ipc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 用于对客户端与服务器的连接协议（ManagerProtocol, ClientProtocol）
 * 注解接口（协议）名称、接口（协议）的版本
 * @author rchen102
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtocolInfo {
    String protocolName(); // 协议名称
    long protocolVersion() default -1; // 协议版本
}
