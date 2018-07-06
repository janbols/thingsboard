/**
 * Copyright © 2016-2018 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.actors.rpc;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.actors.service.ContextAwareActor;
import org.thingsboard.server.actors.service.ContextBasedCreator;
import org.thingsboard.server.actors.service.DefaultActorService;
import org.thingsboard.server.common.msg.TbActorMsg;
import org.thingsboard.server.common.msg.cluster.ClusterEventMsg;
import org.thingsboard.server.common.msg.cluster.ServerAddress;
import org.thingsboard.server.gen.cluster.ClusterAPIProtos;
import org.thingsboard.server.service.cluster.discovery.ServerInstance;

import java.util.*;

/**
 * @author Andrew Shvayka
 */
public class RpcManagerActor extends ContextAwareActor {

    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final Map<ServerAddress, SessionActorInfo> sessionActors;

    private final Map<ServerAddress, Queue<ClusterAPIProtos.ClusterMessage>> pendingMsgs;

    private final ServerAddress instance;

    public RpcManagerActor(ActorSystemContext systemContext) {
        super(systemContext);
        this.sessionActors = new HashMap<>();
        this.pendingMsgs = new HashMap<>();
        this.instance = systemContext.getDiscoveryService().getCurrentServer().getServerAddress();

        systemContext.getDiscoveryService().getOtherServers().stream()
                .filter(otherServer -> otherServer.getServerAddress().compareTo(instance) > 0)
                .forEach(otherServer -> onCreateSessionRequest(
                        new RpcSessionCreateRequestMsg(UUID.randomUUID(), otherServer.getServerAddress(), null)));

    }

    @Override
    protected boolean process(TbActorMsg msg) {
        //TODO Move everything here, to work with TbActorMsg
        return false;
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof ClusterAPIProtos.ClusterMessage) {
            onMsg((ClusterAPIProtos.ClusterMessage) msg);
        } else if (msg instanceof RpcBroadcastMsg) {
            onMsg((RpcBroadcastMsg) msg);
        } else if (msg instanceof RpcSessionCreateRequestMsg) {
            onCreateSessionRequest((RpcSessionCreateRequestMsg) msg);
        } else if (msg instanceof RpcSessionConnectedMsg) {
            onSessionConnected((RpcSessionConnectedMsg) msg);
        } else if (msg instanceof RpcSessionDisconnectedMsg) {
            onSessionDisconnected((RpcSessionDisconnectedMsg) msg);
        } else if (msg instanceof RpcSessionClosedMsg) {
            onSessionClosed((RpcSessionClosedMsg) msg);
        } else if (msg instanceof ClusterEventMsg) {
            onClusterEvent((ClusterEventMsg) msg);
        }
    }

    private void onMsg(RpcBroadcastMsg msg) {
        log.debug("Forwarding msg to session actors {}", msg);
        sessionActors.keySet().forEach(address -> {
            ClusterAPIProtos.ClusterMessage msgWithServerAddress = msg.getMsg()
                    .toBuilder()
                    .setServerAddress(ClusterAPIProtos.ServerAddress
                            .newBuilder()
                            .setHost(address.getHost())
                            .setPort(address.getPort())
                            .build())
                    .build();
            onMsg(msgWithServerAddress);
        });
        pendingMsgs.values().forEach(queue -> queue.add(msg.getMsg()));
    }

    private void onMsg(ClusterAPIProtos.ClusterMessage msg) {
        if (msg.hasServerAddress()) {
            ServerAddress address = new ServerAddress(msg.getServerAddress().getHost(),
                    msg.getServerAddress().getPort());
            SessionActorInfo session = sessionActors.get(address);
            if (session != null) {
                log.debug("{} Forwarding msg to session actor", address);
                session.getActor().tell(msg, ActorRef.noSender());
            } else {
                log.debug("{} Storing msg to pending queue", address);
                Queue<ClusterAPIProtos.ClusterMessage> queue = pendingMsgs.get(address);
                if (queue == null) {
                    queue = new LinkedList<>();
                    pendingMsgs.put(new ServerAddress(
                            msg.getServerAddress().getHost(), msg.getServerAddress().getPort()), queue);
                }
                queue.add(msg);
            }
        } else {
            logger.warning("Cluster msg doesn't have set Server Address [{}]", msg);
        }
    }

    @Override
    public void postStop() {
        sessionActors.clear();
        pendingMsgs.clear();
    }

    private void onClusterEvent(ClusterEventMsg msg) {
        ServerAddress server = msg.getServerAddress();
        if (server.compareTo(instance) > 0) {
            if (msg.isAdded()) {
                onCreateSessionRequest(new RpcSessionCreateRequestMsg(UUID.randomUUID(), server, null));
            } else {
                onSessionClose(false, server);
            }
        }
    }

    private void onSessionConnected(RpcSessionConnectedMsg msg) {
        register(msg.getRemoteAddress(), msg.getId(), context().sender());
    }

    private void onSessionDisconnected(RpcSessionDisconnectedMsg msg) {
        boolean reconnect = msg.isClient() && isRegistered(msg.getRemoteAddress());
        onSessionClose(reconnect, msg.getRemoteAddress());
    }

    private void onSessionClosed(RpcSessionClosedMsg msg) {
        boolean reconnect = msg.isClient() && isRegistered(msg.getRemoteAddress());
        onSessionClose(reconnect, msg.getRemoteAddress());
    }

    private boolean isRegistered(ServerAddress address) {
        for (ServerInstance server : systemContext.getDiscoveryService().getOtherServers()) {
            if (server.getServerAddress().equals(address)) {
                return true;
            }
        }
        return false;
    }

    private void onSessionClose(boolean reconnect, ServerAddress remoteAddress) {
        log.debug("[{}] session closed. Should reconnect: {}", remoteAddress, reconnect);
        SessionActorInfo sessionRef = sessionActors.get(remoteAddress);
        if (context().sender() != null && context().sender().equals(sessionRef.actor)) {
            sessionActors.remove(remoteAddress);
            pendingMsgs.remove(remoteAddress);
            if (reconnect) {
                onCreateSessionRequest(new RpcSessionCreateRequestMsg(sessionRef.sessionId, remoteAddress, null));
            }
        }
    }

    private void onCreateSessionRequest(RpcSessionCreateRequestMsg msg) {
        ActorRef actorRef = createSessionActor(msg);
        if (msg.getRemoteAddress() != null) {
            register(msg.getRemoteAddress(), msg.getMsgUid(), actorRef);
        }
    }

    private void register(ServerAddress remoteAddress, UUID uuid, ActorRef sender) {
        sessionActors.put(remoteAddress, new SessionActorInfo(uuid, sender));
        log.debug("[{}][{}] Registering session actor.", remoteAddress, uuid);
        Queue<ClusterAPIProtos.ClusterMessage> data = pendingMsgs.remove(remoteAddress);
        if (data != null) {
            log.debug("[{}][{}] Forwarding {} pending messages.", remoteAddress, uuid, data.size());
            data.forEach(msg -> sender.tell(new RpcSessionTellMsg(msg), ActorRef.noSender()));
        } else {
            log.debug("[{}][{}] No pending messages to forward.", remoteAddress, uuid);
        }
    }

    private ActorRef createSessionActor(RpcSessionCreateRequestMsg msg) {
        log.debug("[{}] Creating session actor.", msg.getMsgUid());
        ActorRef actor = context().actorOf(
                Props.create(new RpcSessionActor.ActorCreator(systemContext, msg.getMsgUid())).withDispatcher(DefaultActorService.RPC_DISPATCHER_NAME));
        actor.tell(msg, context().self());
        return actor;
    }

    public static class ActorCreator extends ContextBasedCreator<RpcManagerActor> {
        private static final long serialVersionUID = 1L;

        public ActorCreator(ActorSystemContext context) {
            super(context);
        }

        @Override
        public RpcManagerActor create() throws Exception {
            return new RpcManagerActor(context);
        }
    }
}
