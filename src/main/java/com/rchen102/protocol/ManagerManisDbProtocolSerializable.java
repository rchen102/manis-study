package com.rchen102.protocol;

import com.rchen102.ipc.ProtocolInfo;

/**
 * @author rchen102
 */
@ProtocolInfo(protocolName = ManisConstants.MANAGER_MANISDB_PROTOCOL_NAME,
        protocolVersion = 1)
public interface ManagerManisDbProtocolSerializable
        extends ManagerProtocol {
}
