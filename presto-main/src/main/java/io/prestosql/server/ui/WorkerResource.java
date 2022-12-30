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
package io.prestosql.server.ui;

import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.ResponseHandler;
import io.prestosql.dispatcher.DispatchManager;
import io.prestosql.execution.QueryInfo;
import io.prestosql.execution.TaskId;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.InternalNodeManager;
import io.prestosql.metadata.NodeState;
import io.prestosql.security.AccessControl;
import io.prestosql.server.ForWorkerInfo;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.GroupProvider;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static com.google.common.io.ByteStreams.toByteArray;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.prestosql.security.AccessControlUtil.checkCanViewQueryOwnedBy;
import static io.prestosql.server.HttpRequestSessionContext.extractAuthorizedIdentity;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

@Path("/ui/api/worker")
public class WorkerResource
{
    private final DispatchManager dispatchManager;
    private final InternalNodeManager nodeManager;
    private final AccessControl accessControl;
    private final HttpClient httpClient;
    private final GroupProvider groupProvider;

    @Inject
    public WorkerResource(
            DispatchManager dispatchManager,
            InternalNodeManager nodeManager,
            AccessControl accessControl,
            @ForWorkerInfo HttpClient httpClient,
            GroupProvider groupProvider)
    {
        this.dispatchManager = requireNonNull(dispatchManager, "dispatchManager is null");
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.groupProvider = requireNonNull(groupProvider, "groupProvider is null");
    }

    @GET
    @Path("{nodeId}/status")
    public Response getStatus(@PathParam("nodeId") String nodeId)
    {
        return proxyJsonResponse(nodeId, "v1/status");
    }

    @GET
    @Path("{nodeId}/thread")
    public Response getThreads(@PathParam("nodeId") String nodeId)
    {
        return proxyJsonResponse(nodeId, "v1/thread");
    }

    @GET
    @Path("{nodeId}/task/{taskId}")
    public Response getThreads(
            @PathParam("taskId") final TaskId task,
            @PathParam("nodeId") String nodeId,
            @Context HttpServletRequest servletRequest,
            @Context HttpHeaders httpHeaders)
    {
        QueryId queryId = task.getQueryId();
        Optional<QueryInfo> queryInfo = dispatchManager.getFullQueryInfo(queryId);
        if (queryInfo.isPresent()) {
            try {
                checkCanViewQueryOwnedBy(extractAuthorizedIdentity(servletRequest, httpHeaders, accessControl, groupProvider), queryInfo.get().getSession().getUser(), accessControl);
                return proxyJsonResponse(nodeId, "v1/task/" + task);
            }
            catch (AccessDeniedException e) {
                throw new ForbiddenException();
            }
        }
        return Response.status(Status.GONE).build();
    }

    private Response proxyJsonResponse(String nodeId, String workerPath)
    {
        Set<InternalNode> nodes = nodeManager.getNodes(NodeState.ACTIVE);
        InternalNode node = nodes.stream()
                .filter(n -> n.getNodeIdentifier().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new WebApplicationException(NOT_FOUND));

        Request request = prepareGet()
                .setUri(uriBuilderFrom(node.getInternalUri())
                        .appendPath(workerPath)
                        .build())
                .build();

        byte[] responseStream = httpClient.execute(request, new StreamingJsonResponseHandler());
        return Response.ok(responseStream, APPLICATION_JSON_TYPE).build();
    }

    private static class StreamingJsonResponseHandler
            implements ResponseHandler<byte[], RuntimeException>
    {
        @Override
        public byte[] handleException(Request request, Exception exception)
        {
            throw new RuntimeException("Request to worker failed", exception);
        }

        @Override
        public byte[] handle(Request request, io.airlift.http.client.Response response)
        {
            try {
                if (!APPLICATION_JSON.equals(response.getHeader(CONTENT_TYPE))) {
                    throw new RuntimeException("Response received was not of type " + APPLICATION_JSON);
                }
                return toByteArray(response.getInputStream());
            }
            catch (IOException e) {
                throw new RuntimeException("Unable to read response from worker", e);
            }
        }
    }
}
