package com.rchen102.protocolPB;

import com.rchen102.ipc.ProtocolInfo;
import com.rchen102.protocol.ManisConstants;
import com.rchen102.protocol.proto.ClientManisDbProtocolProtos;

/**
 * @author rchen102
 */
@ProtocolInfo(protocolName = ManisConstants.CLIENT_MANISDB_PROTOCOL_NAME,
        protocolVersion = 1)
public interface ClientManisDbProtocolPB extends
        ClientManisDbProtocolProtos.ClientManisDbProtocol.BlockingInterface {
}
