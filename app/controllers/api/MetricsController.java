package controllers.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import controllers.AuthenticatedController;
import lib.SockJSUtils;
import lib.json.Json;
import lib.security.RedirectAuthenticator;
import lib.sockjs.SockJsRouter;
import models.sockjs.CreateSessionCommand;
import models.sockjs.MetricValuesUpdate;
import models.sockjs.SockJsCommand;
import models.sockjs.SubscribeMetricsUpdates;
import org.graylog2.restclient.lib.APIException;
import org.graylog2.restclient.lib.ApiClient;
import org.graylog2.restclient.lib.ApiRequestBuilder;
import org.graylog2.restclient.lib.Graylog2ServerUnavailableException;
import org.graylog2.restclient.lib.metrics.Metric;
import org.graylog2.restclient.models.Node;
import org.graylog2.restclient.models.NodeService;
import org.graylog2.restclient.models.api.requests.MultiMetricRequest;
import org.graylog2.restclient.models.api.responses.metrics.MetricsListResponse;
import org.slf4j.LoggerFactory;
import play.Play;
import play.libs.F;
import play.sockjs.CookieCalculator;
import play.sockjs.ScriptLocation;
import play.sockjs.SockJS;

import javax.inject.Inject;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.graylog2.restroutes.generated.routes.MetricsResource;

public class MetricsController extends AuthenticatedController {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final NodeService nodeService;
    private final ScheduledExecutorService executor;

    private static ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE);

    @Inject
    public MetricsController(NodeService nodeService) {
        executor = Executors.newSingleThreadScheduledExecutor();
        this.nodeService = nodeService;
    }

    private static class PushResponse {
        public final Set<MetricValuesUpdate> metrics = Sets.newHashSet();
        public boolean hasError = false;
    }

    public static SockJsRouter metrics = new MySockJSRouter();

    private static class PollingJob implements Runnable {
        private final AtomicReference<String> clearSessionId;
        private final MetricsController controllerInstance;
        private final SockJS.Out out;
        private final Multimap<String, String> metricsPerNode;

        public PollingJob(AtomicReference<String> clearSessionId,
                          MetricsController controllerInstance,
                          SockJS.Out out,
                          Multimap<String, String> metricsPerNode) {
            this.clearSessionId = clearSessionId;
            this.controllerInstance = controllerInstance;
            this.out = out;
            this.metricsPerNode = metricsPerNode;
        }

        @Override
        public void run() {
            final PushResponse pushResponse = new PushResponse();
            // collects all metrics per node, so we don't send duplicate updates for any given node id
            final HashMultimap<String, Map.Entry<String, Metric>> entries = HashMultimap.create();

            try {
                // don't send anything if not authenticated or nothing is requested
                if (clearSessionId.get() == null || metricsPerNode.isEmpty()) {
                    return;
                }
                final ApiClient api = controllerInstance.api();

                for (String nodeId : metricsPerNode.keySet()) {
                    try {
                        final MultiMetricRequest request = new MultiMetricRequest();
                        Collection<String> metricNames = metricsPerNode.get(nodeId);
                        request.metrics = metricNames.toArray(new String[metricNames.size()]);

                        // base request builder, in the next step we decide which node to ask
                        ApiRequestBuilder<MetricsListResponse> requestBuilder = api.path(
                                MetricsResource().multipleMetrics(),
                                MetricsListResponse.class)
                                .body(request)
                                .session(clearSessionId.get())
                                .extendSession(false)
                                .expect(200);

                        // either query every known node in the cluster, or just one
                        if (nodeId == null) {
                            final Map<Node, MetricsListResponse> responseMap = requestBuilder.fromAllNodes().executeOnAll();

                            for (Map.Entry<Node, MetricsListResponse> perNodeEntry : responseMap.entrySet()) {
                                entries.putAll(perNodeEntry.getKey().getNodeId(), perNodeEntry.getValue().getMetrics().entrySet());
                            }
                        } else {
                            try {
                                final Node node = controllerInstance.nodeService.loadNode(nodeId);
                                final MetricsListResponse response = requestBuilder.node(node).execute();
                                entries.putAll(node.getNodeId(), response.getMetrics().entrySet());
                            } catch (NodeService.NodeNotFoundException e) {
                                log.warn("Unknown node {}, skipping it.", nodeId);
                            }
                        }

                    } catch (APIException | IOException e) {
                        pushResponse.hasError = true;
                        log.warn("Unable to load metrics", e);
                    }
                }
            } catch (Graylog2ServerUnavailableException e) {
                pushResponse.hasError = true;
            } catch (Exception e) {
                pushResponse.hasError = true;
                log.warn("Unhandled exception, catching to prevent scheduled task from ending.", e);
            }
            try {
                for (String nodeId : entries.keySet()) {
                    pushResponse.metrics.add(createMetricUpdate(nodeId, entries.get(nodeId)));
                }
                out.write(Json.toJsonString(pushResponse));
            } catch (Exception e){
                log.error("Unhandled exception, catching to prevent scheduled task from ending, this is a bug.", e);
            }
        }

        private MetricValuesUpdate createMetricUpdate(String nodeId, Set<Map.Entry<String, Metric>> metrics) {
            final MetricValuesUpdate valuesUpdate = new MetricValuesUpdate(nodeId);
            for (Map.Entry<String, Metric> entry : metrics) {
                valuesUpdate.values.add(new MetricValuesUpdate.NamedMetric(entry.getKey(), entry.getValue()));
            }
            return valuesUpdate;
        }
    }

    private static class MySockJSRouter extends SockJsRouter {

        // defaults: ScriptLocation.DefaultCdn.class, CookieCalculator.None.class, true, 25000, 5000, 128*1024
        public MySockJSRouter() {
            super(F.<SockJS.Settings>Some(new SockJS.Settings() {
                @Override
                public Class<? extends ScriptLocation> script() {
                    return ScriptLocation.DefaultCdn.class;
                }

                @Override
                public Class<? extends CookieCalculator> cookies() {
                    return CookieCalculator.None.class;
                }

                @Override
                public boolean websocket() {
                    return Boolean.valueOf(SockJSUtils.isWebsocketsEnabled());
                }

                @Override
                public long heartbeat() {
                    return 25000;
                }

                @Override
                public long sessionTimeout() {
                    return 5000;
                }

                @Override
                public long streamingQuota() {
                    return 128 * 1024;
                }

                @Override
                public Class<? extends Annotation> annotationType() {
                    return SockJS.Settings.class;
                }
            }));
        }

        @Override
        public SockJS sockjs() {

            // this is the dance to get DI working
            final MetricsController controllerInstance =
                    Play.application()
                            .getWrappedApplication()
                            .global()
                            .getControllerInstance(MetricsController.class);

            return new SockJS() {

                private Multimap<String, String> metricsPerNode =
                        Multimaps.synchronizedMultimap(HashMultimap.<String, String>create());

                private final AtomicReference<String> clearSessionId = new AtomicReference<>(null);

                @Override
                public void onReady(final In in, final Out out) {

                    in.onMessage(new F.Callback<String>() {
                        @Override
                        public void invoke(String s) throws Throwable {
                            try {
                                final SockJsCommand command = objectMapper.readValue(s, SockJsCommand.class);
                                if (command instanceof CreateSessionCommand) {

                                    final String sessionId = ((CreateSessionCommand) command).sessionId;

                                    final String[] userAndSessionId = RedirectAuthenticator.decodeSession(sessionId);
                                    if (userAndSessionId == null) {
                                        log.warn("No valid session id, cannot load metrics.");
                                        return;
                                    }
                                    clearSessionId.set(userAndSessionId[1]);
                                } else if (command instanceof SubscribeMetricsUpdates) {
                                    final SubscribeMetricsUpdates metricsUpdates = (SubscribeMetricsUpdates) command;
                                    log.debug("Subscribed to metrics {} on node {}",
                                                metricsUpdates.metrics,
                                                MoreObjects.firstNonNull(metricsUpdates.nodeId, "ALL"));

                                    metricsPerNode.putAll(metricsUpdates.nodeId, metricsUpdates.metrics);
                                }
                            } catch (Exception e) {
                                log.error("Unhandled exception", e);
                            }
                        }
                    });

                    final ScheduledFuture<?> scheduledFuture = controllerInstance.executor.scheduleAtFixedRate(
                            new PollingJob(clearSessionId, controllerInstance, out, metricsPerNode), 0, 1, TimeUnit.SECONDS);

                    in.onClose(new F.Callback0() {
                        @Override
                        public void invoke() throws Throwable {
                            scheduledFuture.cancel(true);
                            controllerInstance.executor.shutdown();
                        }
                    });

                }
            };
        }
    }
}
