/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.metadata;

import com.facebook.presto.client.NodeVersion;
import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.connector.system.GlobalSystemConnector;
import com.facebook.presto.failureDetector.FailureDetector;
import com.facebook.presto.server.InternalCommunicationConfig;
import com.facebook.presto.spi.Node;
import com.facebook.presto.spi.NodeState;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import io.airlift.discovery.client.ServiceDescriptor;
import io.airlift.discovery.client.ServiceSelector;
import io.airlift.discovery.client.ServiceType;
import io.airlift.http.client.HttpClient;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.airlift.units.Duration;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.spi.NodeState.ACTIVE;
import static com.facebook.presto.spi.NodeState.INACTIVE;
import static com.facebook.presto.spi.NodeState.SHUTTING_DOWN;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

@ThreadSafe
public final class DiscoveryNodeManager
        implements InternalNodeManager
{
    private static final Logger log = Logger.get(DiscoveryNodeManager.class);
    private static final Duration MAX_AGE = new Duration(5, TimeUnit.SECONDS);

    private static final Splitter CONNECTOR_ID_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
    private final ServiceSelector serviceSelector;
    private final NodeInfo nodeInfo;
    private final FailureDetector failureDetector;
    private final NodeVersion expectedNodeVersion;
    private final ConcurrentHashMap<String, RemoteNodeState> nodeStates = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final ScheduledExecutorService nodeStateUpdateExecutor;
    private final boolean httpsRequired;

    @GuardedBy("this")
    private SetMultimap<ConnectorId, Node> activeNodesByConnectorId;

    @GuardedBy("this")
    private AllNodes allNodes;

    @GuardedBy("this")
    private long lastUpdateTimestamp;

    private final PrestoNode currentNode;

    @GuardedBy("this")
    private Set<Node> coordinators;

    @Inject
    public DiscoveryNodeManager(
            @ServiceType("presto") ServiceSelector serviceSelector,
            NodeInfo nodeInfo,
            FailureDetector failureDetector,
            NodeVersion expectedNodeVersion,
            @ForNodeManager HttpClient httpClient,
            InternalCommunicationConfig internalCommunicationConfig)
    {
        this.serviceSelector = requireNonNull(serviceSelector, "serviceSelector is null");
        this.nodeInfo = requireNonNull(nodeInfo, "nodeInfo is null");
        this.failureDetector = requireNonNull(failureDetector, "failureDetector is null");
        this.expectedNodeVersion = requireNonNull(expectedNodeVersion, "expectedNodeVersion is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.nodeStateUpdateExecutor = newSingleThreadScheduledExecutor(threadsNamed("node-state-poller-%s"));
        this.httpsRequired = internalCommunicationConfig.isHttpsRequired();
        this.currentNode = refreshNodesInternal();
    }

    @PostConstruct
    public void startPollingNodeStates()
    {
        // poll worker states only on the coordinators
        if (getCoordinators().contains(currentNode)) {
            nodeStateUpdateExecutor.scheduleWithFixedDelay(() -> {
                ImmutableSet.Builder nodeSetBuilder = ImmutableSet.builder();
                AllNodes allNodes = getAllNodes();
                Set<Node> aliveNodes = nodeSetBuilder
                        .addAll(allNodes.getActiveNodes())
                        .addAll(allNodes.getShuttingDownNodes())
                        .build();

                ImmutableSet<String> aliveNodeIds = aliveNodes.stream()
                        .map(Node::getNodeIdentifier)
                        .collect(toImmutableSet());

                // Remove nodes that don't exist anymore
                // Make a copy to materialize the set difference
                Set<String> deadNodes = difference(nodeStates.keySet(), aliveNodeIds).immutableCopy();
                nodeStates.keySet().removeAll(deadNodes);

                // Add new nodes
                for (Node node : aliveNodes) {
                    nodeStates.putIfAbsent(node.getNodeIdentifier(),
                            new RemoteNodeState(httpClient, uriBuilderFrom(node.getHttpUri()).appendPath("/v1/info/state").build()));
                }

                // Schedule refresh
                nodeStates.values().forEach(RemoteNodeState::asyncRefresh);
            }, 1, 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void stop()
    {
        nodeStateUpdateExecutor.shutdownNow();
    }

    @Override
    public void refreshNodes()
    {
        refreshNodesInternal();
    }

    private synchronized PrestoNode refreshNodesInternal()
    {
        lastUpdateTimestamp = System.nanoTime();

        // This is currently a blacklist.
        // TODO: make it a whitelist (a failure-detecting service selector) and maybe build in support for injecting this in airlift
        Set<ServiceDescriptor> services = serviceSelector.selectAllServices().stream()
                .filter(service -> !failureDetector.getFailed().contains(service))
                .collect(toImmutableSet());

        PrestoNode currentNode = null;

        ImmutableSet.Builder<Node> activeNodesBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<Node> inactiveNodesBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<Node> shuttingDownNodesBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<Node> coordinatorsBuilder = ImmutableSet.builder();
        ImmutableSetMultimap.Builder<ConnectorId, Node> byConnectorIdBuilder = ImmutableSetMultimap.builder();

        for (ServiceDescriptor service : services) {
            URI uri = getHttpUri(service);
            NodeVersion nodeVersion = getNodeVersion(service);
            boolean coordinator = isCoordinator(service);
            if (uri != null && nodeVersion != null) {
                PrestoNode node = new PrestoNode(service.getNodeId(), uri, nodeVersion, coordinator);
                NodeState nodeState = getNodeState(node);

                // record current node
                if (node.getNodeIdentifier().equals(nodeInfo.getNodeId())) {
                    currentNode = node;
                    checkState(currentNode.getNodeVersion().equals(expectedNodeVersion), "INVARIANT: current node version (%s) should be equal to %s", currentNode.getNodeVersion(), expectedNodeVersion);
                }

                switch (nodeState) {
                    case ACTIVE:
                        activeNodesBuilder.add(node);
                        if (coordinator) {
                            coordinatorsBuilder.add(node);
                        }

                        // record available active nodes organized by connector id
                        String connectorIds = service.getProperties().get("connectorIds");
                        if (connectorIds != null) {
                            connectorIds = connectorIds.toLowerCase(ENGLISH);
                            for (String connectorId : CONNECTOR_ID_SPLITTER.split(connectorIds)) {
                                byConnectorIdBuilder.put(new ConnectorId(connectorId), node);
                            }
                        }

                        // always add system connector
                        byConnectorIdBuilder.put(new ConnectorId(GlobalSystemConnector.NAME), node);
                        break;
                    case INACTIVE:
                        inactiveNodesBuilder.add(node);
                        break;
                    case SHUTTING_DOWN:
                        shuttingDownNodesBuilder.add(node);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown node state " + nodeState);
                }
            }
        }

        if (allNodes != null) {
            // log node that are no longer active (but not shutting down)
            SetView<Node> missingNodes = difference(allNodes.getActiveNodes(), Sets.union(activeNodesBuilder.build(), shuttingDownNodesBuilder.build()));
            for (Node missingNode : missingNodes) {
                log.info("Previously active node is missing: %s (last seen at %s)", missingNode.getNodeIdentifier(), missingNode.getHostAndPort());
            }
        }

        allNodes = new AllNodes(activeNodesBuilder.build(), inactiveNodesBuilder.build(), shuttingDownNodesBuilder.build());
        activeNodesByConnectorId = byConnectorIdBuilder.build();
        coordinators = coordinatorsBuilder.build();

        checkState(currentNode != null, "INVARIANT: current node not returned from service selector");
        return currentNode;
    }

    private synchronized void refreshIfNecessary()
    {
        if (Duration.nanosSince(lastUpdateTimestamp).compareTo(MAX_AGE) > 0) {
            refreshNodesInternal();
        }
    }

    private NodeState getNodeState(PrestoNode node)
    {
        if (expectedNodeVersion.equals(node.getNodeVersion())) {
            if (isNodeShuttingDown(node.getNodeIdentifier())) {
                return SHUTTING_DOWN;
            }
            else {
                return ACTIVE;
            }
        }
        else {
            return INACTIVE;
        }
    }

    private boolean isNodeShuttingDown(String nodeId)
    {
        Optional<NodeState> remoteNodeState = nodeStates.containsKey(nodeId)
                ? nodeStates.get(nodeId).getNodeState()
                : Optional.empty();
        return remoteNodeState.isPresent() && remoteNodeState.get().equals(SHUTTING_DOWN);
    }

    @Override
    public synchronized AllNodes getAllNodes()
    {
        refreshIfNecessary();
        return allNodes;
    }

    @Managed
    public int getActiveNodeCount()
    {
        return getAllNodes().getActiveNodes().size();
    }

    @Managed
    public int getInactiveNodeCount()
    {
        return getAllNodes().getInactiveNodes().size();
    }

    @Managed
    public int getShuttingDownNodeCount()
    {
        return getAllNodes().getShuttingDownNodes().size();
    }

    @Override
    public Set<Node> getNodes(NodeState state)
    {
        switch (state) {
            case ACTIVE:
                return getAllNodes().getActiveNodes();
            case INACTIVE:
                return getAllNodes().getInactiveNodes();
            case SHUTTING_DOWN:
                return getAllNodes().getShuttingDownNodes();
            default:
                throw new IllegalArgumentException("Unknown node state " + state);
        }
    }

    @Override
    public synchronized Set<Node> getActiveConnectorNodes(ConnectorId connectorId)
    {
        refreshIfNecessary();
        return activeNodesByConnectorId.get(connectorId);
    }

    @Override
    public Node getCurrentNode()
    {
        return currentNode;
    }

    @Override
    public synchronized Set<Node> getCoordinators()
    {
        refreshIfNecessary();
        return coordinators;
    }

    private URI getHttpUri(ServiceDescriptor descriptor)
    {
        String url = descriptor.getProperties().get(httpsRequired ? "https" : "http");
        if (url != null) {
            try {
                return new URI(url);
            }
            catch (URISyntaxException ignored) {
            }
        }
        return null;
    }

    private static NodeVersion getNodeVersion(ServiceDescriptor descriptor)
    {
        String nodeVersion = descriptor.getProperties().get("node_version");
        return nodeVersion == null ? null : new NodeVersion(nodeVersion);
    }

    private static boolean isCoordinator(ServiceDescriptor service)
    {
        return Boolean.parseBoolean(service.getProperties().get("coordinator"));
    }
}
