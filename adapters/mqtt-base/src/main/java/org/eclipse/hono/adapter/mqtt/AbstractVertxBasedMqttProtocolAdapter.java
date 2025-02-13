/*******************************************************************************
 * Copyright (c) 2016, 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.adapter.mqtt;

import java.net.HttpURLConnection;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.net.ssl.SSLSession;

import org.eclipse.hono.adapter.AbstractProtocolAdapterBase;
import org.eclipse.hono.adapter.AdapterConnectionsExceededException;
import org.eclipse.hono.adapter.AuthorizationException;
import org.eclipse.hono.adapter.auth.device.AuthHandler;
import org.eclipse.hono.adapter.auth.device.ChainAuthHandler;
import org.eclipse.hono.adapter.auth.device.CredentialsApiAuthProvider;
import org.eclipse.hono.adapter.auth.device.DeviceCredentials;
import org.eclipse.hono.adapter.auth.device.TenantServiceBasedX509Authentication;
import org.eclipse.hono.adapter.auth.device.UsernamePasswordAuthProvider;
import org.eclipse.hono.adapter.auth.device.X509AuthProvider;
import org.eclipse.hono.adapter.limiting.ConnectionLimitManager;
import org.eclipse.hono.adapter.limiting.DefaultConnectionLimitManager;
import org.eclipse.hono.adapter.limiting.MemoryBasedConnectionLimitStrategy;
import org.eclipse.hono.adapter.mqtt.MqttContext.ErrorHandlingMode;
import org.eclipse.hono.auth.Device;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.client.command.Command;
import org.eclipse.hono.client.command.CommandConsumer;
import org.eclipse.hono.client.command.CommandContext;
import org.eclipse.hono.client.command.CommandResponse;
import org.eclipse.hono.notification.NotificationEventBusSupport;
import org.eclipse.hono.notification.deviceregistry.AllDevicesOfTenantDeletedNotification;
import org.eclipse.hono.notification.deviceregistry.DeviceChangeNotification;
import org.eclipse.hono.notification.deviceregistry.LifecycleChange;
import org.eclipse.hono.notification.deviceregistry.TenantChangeNotification;
import org.eclipse.hono.service.auth.DeviceUser;
import org.eclipse.hono.service.metric.MetricsTags;
import org.eclipse.hono.service.metric.MetricsTags.ConnectionAttemptOutcome;
import org.eclipse.hono.service.metric.MetricsTags.Direction;
import org.eclipse.hono.service.metric.MetricsTags.EndpointType;
import org.eclipse.hono.service.metric.MetricsTags.ProcessingOutcome;
import org.eclipse.hono.tracing.TenantTraceSamplingHelper;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.Futures;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.Pair;
import org.eclipse.hono.util.RegistrationAssertion;
import org.eclipse.hono.util.ResourceIdentifier;
import org.eclipse.hono.util.TenantObject;

import io.micrometer.core.instrument.Timer.Sample;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.mqtt.MqttConnectionException;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;

/**
 * A base class for implementing Vert.x based Hono protocol adapters for publishing events &amp; telemetry data using
 * MQTT.
 *
 * @param <T> The type of configuration properties this adapter supports/requires.
 */
public abstract class AbstractVertxBasedMqttProtocolAdapter<T extends MqttProtocolAdapterProperties>
        extends AbstractProtocolAdapterBase<T> {

    /**
     * The minimum amount of memory (bytes) that the adapter requires to run on a standard Java VM.
     */
    protected static final int MINIMAL_MEMORY_JVM = 100_000_000;
    /**
     * The minimum amount of memory (bytes) that the adapter requires to run on a Substrate VM (i.e. when running
     * as a GraalVM native image).
     */
    protected static final int MINIMAL_MEMORY_SUBSTRATE = 35_000_000;
    /**
     * The amount of memory (bytes) required for each connection.
     */
    protected static final int MEMORY_PER_CONNECTION = 20_000;
    /**
     * The number of bytes added to the configured max payload size to set the max MQTT message size.
     * Accounts for the size of the variable header (including the topic name) included in the max message size
     * calculation.
     */
    protected static final int MAX_MSG_SIZE_VARIABLE_HEADER_SIZE = 128;

    private static final String EVENT_SENDING_PUBACK = "sending PUBACK";
    private static final int IANA_MQTT_PORT = 1883;
    private static final int IANA_SECURE_MQTT_PORT = 8883;
    private static final String LOG_FIELD_TOPIC_FILTER = "filter";

    private final AtomicReference<Promise<Void>> stopResultPromiseRef = new AtomicReference<>();

    private MqttAdapterMetrics metrics = MqttAdapterMetrics.NOOP;

    private MqttServer server;
    private MqttServer insecureServer;
    private AuthHandler<MqttConnectContext> authHandler;

    /**
     * The endpoints representing the currently connected, authenticated MQTT devices.
     */
    private final Set<MqttDeviceEndpoint> connectedAuthenticatedDeviceEndpoints = new HashSet<>();

    /**
     * Sets the authentication handler to use for authenticating devices.
     *
     * @param authHandler The handler to use.
     * @throws NullPointerException if handler is {@code null}.
     */
    public final void setAuthHandler(final AuthHandler<MqttConnectContext> authHandler) {
        this.authHandler = Objects.requireNonNull(authHandler);
    }

    @Override
    public int getPortDefaultValue() {
        return IANA_SECURE_MQTT_PORT;
    }

    @Override
    public int getInsecurePortDefaultValue() {
        return IANA_MQTT_PORT;
    }

    @Override
    protected final int getActualPort() {
        return server != null ? server.actualPort() : Constants.PORT_UNCONFIGURED;
    }

    @Override
    protected final int getActualInsecurePort() {
        return insecureServer != null ? insecureServer.actualPort() : Constants.PORT_UNCONFIGURED;
    }

    /**
     * Sets the metrics for this service.
     *
     * @param metrics The metrics
     */
    public final void setMetrics(final MqttAdapterMetrics metrics) {
        Optional.ofNullable(metrics)
            .ifPresent(m -> log.info("reporting metrics using [{}]", metrics.getClass().getName()));
        this.metrics = metrics;
    }

    /**
     * Gets the metrics for this service.
     *
     * @return The metrics
     */
    protected final MqttAdapterMetrics getMetrics() {
        return metrics;
    }

    /**
     * Creates the default auth handler to use for authenticating devices.
     * <p>
     * This default implementation creates a {@link ChainAuthHandler} consisting of
     * an {@link X509AuthHandler} and a {@link ConnectPacketAuthHandler} instance.
     * <p>
     * Subclasses may either set the auth handler explicitly using
     * {@link #setAuthHandler(AuthHandler)} or override this method in order to
     * create a custom auth handler.
     *
     * @return The handler.
     */
    protected AuthHandler<MqttConnectContext> createAuthHandler() {

        return new ChainAuthHandler<>(this::handleBeforeCredentialsValidation)
                .append(new X509AuthHandler(
                        new TenantServiceBasedX509Authentication(getTenantClient(), tracer),
                        new X509AuthProvider(getCredentialsClient(), tracer)))
                .append(new ConnectPacketAuthHandler(
                        new UsernamePasswordAuthProvider(
                                getCredentialsClient(),
                                tracer)));
    }

    /**
     * Handles any operations that should be invoked as part of the authentication process after the credentials got
     * determined and before they get validated. Can be used to perform checks using the credentials and tenant
     * information before the potentially expensive credentials validation is done
     * <p>
     * The default implementation updates the trace sampling priority in the execution context tracing span.
     * It also verifies that the tenant provided via the credentials is enabled and that the adapter is enabled for
     * that tenant, failing the returned future if either is not the case.
     * <p>
     * Subclasses should override this method in order to perform additional operations after calling this super method.
     *
     * @param credentials The credentials.
     * @param executionContext The execution context, including the TenantObject.
     * @return A future indicating the outcome of the operation. A failed future will fail the authentication attempt.
     */
    protected Future<Void> handleBeforeCredentialsValidation(final DeviceCredentials credentials,
            final MqttConnectContext executionContext) {

        final String tenantId = credentials.getTenantId();
        final Span span = executionContext.getTracingSpan();
        final String authId = credentials.getAuthId();

        return getTenantConfiguration(tenantId, span.context())
                .recover(t -> Future.failedFuture(CredentialsApiAuthProvider.mapNotFoundToBadCredentialsException(t)))
                .map(tenantObject -> {
                    TracingHelper.setDeviceTags(span, tenantId, null, authId);
                    final OptionalInt traceSamplingPriority = TenantTraceSamplingHelper.applyTraceSamplingPriority(
                            tenantObject, authId, span);
                    executionContext.setTraceSamplingPriority(traceSamplingPriority);
                    return tenantObject;
                })
                .compose(this::isAdapterEnabled)
                .mapEmpty();
    }

    /**
     * Sets the MQTT server to use for handling secure MQTT connections.
     *
     * @param server The server.
     * @throws NullPointerException if the server is {@code null}.
     * @throws IllegalStateException if the adapter has already been started.
     */
    public void setMqttSecureServer(final MqttServer server) {
        Objects.requireNonNull(server);
        if (server.actualPort() > 0) {
            throw new IllegalStateException("MQTT server must not be started already");
        } else {
            this.server = server;
        }
    }

    /**
     * Sets the MQTT server to use for handling non-secure MQTT connections.
     *
     * @param server The server.
     * @throws NullPointerException if the server is {@code null}.
     * @throws IllegalStateException if the adapter has already been started.
     */
    public void setMqttInsecureServer(final MqttServer server) {
        Objects.requireNonNull(server);
        if (server.actualPort() > 0) {
            throw new IllegalStateException("MQTT server must not be started already");
        } else {
            this.insecureServer = server;
        }
    }

    private Future<Void> bindSecureMqttServer() {

        if (isSecurePortEnabled()) {
            final MqttServerOptions options = new MqttServerOptions();
            options
                    .setHost(getConfig().getBindAddress())
                    .setPort(determineSecurePort())
                    .setMaxMessageSize(getConfig().getMaxPayloadSize() + MAX_MSG_SIZE_VARIABLE_HEADER_SIZE);
            addTlsKeyCertOptions(options);
            addTlsTrustOptions(options);

            return bindMqttServer(options, server).map(s -> {
                server = s;
                return (Void) null;
            });
        } else {
            return Future.succeededFuture();
        }
    }

    private Future<Void> bindInsecureMqttServer() {

        if (isInsecurePortEnabled()) {
            final MqttServerOptions options = new MqttServerOptions();
            options
                    .setHost(getConfig().getInsecurePortBindAddress())
                    .setPort(determineInsecurePort())
                    .setMaxMessageSize(getConfig().getMaxPayloadSize() + MAX_MSG_SIZE_VARIABLE_HEADER_SIZE);

            return bindMqttServer(options, insecureServer).map(createdServer -> {
                insecureServer = createdServer;
                return (Void) null;
            });
        } else {
            return Future.succeededFuture();
        }
    }

    private Future<MqttServer> bindMqttServer(final MqttServerOptions options, final MqttServer mqttServer) {

        final Promise<MqttServer> result = Promise.promise();
        final MqttServer createdMqttServer = mqttServer == null ? MqttServer.create(this.vertx, options) : mqttServer;

        createdMqttServer
                .endpointHandler(this::handleEndpointConnection)
                .listen(done -> {

                    if (done.succeeded()) {
                        log.info("MQTT server running on {}:{}", getConfig().getBindAddress(),
                                createdMqttServer.actualPort());
                        result.complete(createdMqttServer);
                    } else {
                        log.error("error while starting up MQTT server", done.cause());
                        result.fail(done.cause());
                    }
                });
        return result.future();
    }

    @Override
    protected final void doStart(final Promise<Void> startPromise) {

        registerDeviceAndTenantChangeNotificationConsumers();

        log.info("limiting size of inbound message payload to {} bytes", getConfig().getMaxPayloadSize());
        if (!getConfig().isAuthenticationRequired()) {
            log.warn("authentication of devices turned off");
        }

        final ConnectionLimitManager connectionLimitManager = Optional.ofNullable(
                getConnectionLimitManager()).orElseGet(this::createConnectionLimitManager);
        setConnectionLimitManager(connectionLimitManager);

        checkPortConfiguration()
            .compose(ok -> CompositeFuture.all(bindSecureMqttServer(), bindInsecureMqttServer()))
            .compose(ok -> {
                if (authHandler == null) {
                    authHandler = createAuthHandler();
                }
                return Future.succeededFuture((Void) null);
            })
            .onComplete(startPromise);
    }

    private void registerDeviceAndTenantChangeNotificationConsumers() {
        NotificationEventBusSupport.registerConsumer(vertx, DeviceChangeNotification.TYPE,
                notification -> {
                    if (LifecycleChange.DELETE.equals(notification.getChange())
                            || (LifecycleChange.UPDATE.equals(notification.getChange()) && !notification.isEnabled())) {
                        final String reason = LifecycleChange.DELETE.equals(notification.getChange()) ? "device deleted"
                                : "device disabled";
                        closeDeviceConnectionsOnDeviceOrTenantChange(
                                connectedDevice -> connectedDevice.getTenantId().equals(notification.getTenantId())
                                        && connectedDevice.getDeviceId().equals(notification.getDeviceId()),
                                reason);
                    }
                });
        NotificationEventBusSupport.registerConsumer(vertx, AllDevicesOfTenantDeletedNotification.TYPE,
                notification -> closeDeviceConnectionsOnDeviceOrTenantChange(
                        connectedDevice -> connectedDevice.getTenantId().equals(notification.getTenantId()),
                        "all devices of tenant deleted"));
        NotificationEventBusSupport.registerConsumer(vertx, TenantChangeNotification.TYPE,
                notification -> {
                    if (LifecycleChange.DELETE.equals(notification.getChange())
                            || (LifecycleChange.UPDATE.equals(notification.getChange()) && !notification.isEnabled())) {
                        final String reason = LifecycleChange.DELETE.equals(notification.getChange()) ? "tenant deleted"
                                : "tenant disabled";
                        closeDeviceConnectionsOnDeviceOrTenantChange(
                                connectedDevice -> connectedDevice.getTenantId().equals(notification.getTenantId()),
                                reason);
                    }
                });
    }

    private void closeDeviceConnectionsOnDeviceOrTenantChange(final Predicate<Device> deviceMatchPredicate, final String reason) {
        final List<MqttDeviceEndpoint> deviceEndpoints = connectedAuthenticatedDeviceEndpoints.stream()
                .filter(ep -> deviceMatchPredicate.test(ep.getAuthenticatedDevice()))
                .collect(Collectors.toList()); // using intermediate list since connectedAuthenticatedDeviceEndpoints is modified in the close() method
        deviceEndpoints.forEach(ep -> {
            // decouple the (potentially numerous) close invocations by using context.runOnContext()
            Futures.onCurrentContextCompletionHandler(v -> {
                // sendDisconnectedEvent param false here - sending an event for a deleted/disabled tenant/device would fail
                ep.close(reason, false);
            }).handle(null);
        });
    }

    private ConnectionLimitManager createConnectionLimitManager() {
        return new DefaultConnectionLimitManager(
                MemoryBasedConnectionLimitStrategy.forParams(
                        getConfig().isSubstrateVm() ? MINIMAL_MEMORY_SUBSTRATE : MINIMAL_MEMORY_JVM,
                        MEMORY_PER_CONNECTION,
                        getConfig().getGcHeapPercentage()),
                () -> metrics.getNumberOfConnections(), getConfig());
    }

    @Override
    protected final void doStop(final Promise<Void> stopPromise) {
        if (!stopResultPromiseRef.compareAndSet(null, stopPromise)) {
            stopResultPromiseRef.get().future().onComplete(stopPromise);
            log.trace("stop already called");
            return;
        }
        final Promise<Void> serverTracker = Promise.promise();
        if (this.server != null) {
            log.info("closing secure MQTT server ...");
            this.server.close(serverTracker);
        } else {
            serverTracker.complete();
        }

        final Promise<Void> insecureServerTracker = Promise.promise();
        if (this.insecureServer != null) {
            log.info("closing insecure MQTT server ...");
            this.insecureServer.close(insecureServerTracker);
        } else {
            insecureServerTracker.complete();
        }

        CompositeFuture.all(serverTracker.future(), insecureServerTracker.future())
            .map(ok -> (Void) null)
            .onComplete(ar -> log.info("MQTT server(s) closed"))
            .onComplete(stopPromise);
    }

    /**
     * Invoked when a client sends its <em>CONNECT</em> packet.
     * <p>
     * Authenticates the client (if required) and registers handlers for processing messages published by the client.
     *
     * @param endpoint The MQTT endpoint representing the client.
     */
    final void handleEndpointConnection(final MqttEndpoint endpoint) {

        log.debug("connection request from client [client-id: {}]", endpoint.clientIdentifier());
        final String cipherSuite = Optional.ofNullable(endpoint.sslSession()).map(SSLSession::getCipherSuite)
                .orElse(null);
        final Span span = tracer.buildSpan("CONNECT")
                .ignoreActiveSpan()
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                .withTag(Tags.COMPONENT.getKey(), getTypeName())
                .withTag(TracingHelper.TAG_CLIENT_ID.getKey(), endpoint.clientIdentifier())
                .start();

        if (!endpoint.isCleanSession()) {
            span.log("ignoring client's intent to resume existing session");
        }
        if (endpoint.will() != null) {
            span.log("ignoring client's last will");
        }
        final AtomicBoolean connectionClosedPrematurely = new AtomicBoolean(false);
        // set a preliminary close handler, will be overwritten at the end of handleConnectionRequest()
        endpoint.closeHandler(v -> {
            log.debug("client closed connection before CONNACK got sent to client [client-id: {}]", endpoint.clientIdentifier());
            TracingHelper.logError(span, "client closed connection");
            connectionClosedPrematurely.set(true);
        });

        handleConnectionRequest(endpoint, connectionClosedPrematurely, span)
            .compose(authenticatedDevice -> handleConnectionRequestResult(endpoint, authenticatedDevice, connectionClosedPrematurely, span))
            .onSuccess(authenticatedDevice -> {
                // we NEVER maintain session state
                endpoint.accept(false);
                span.log("connection accepted");
                metrics.reportConnectionAttempt(
                        ConnectionAttemptOutcome.SUCCEEDED,
                        Optional.ofNullable(authenticatedDevice).map(Device::getTenantId).orElse(null),
                        cipherSuite);
            })
            .onFailure(t -> {
                if (!connectionClosedPrematurely.get()) {
                    log.debug("rejecting connection request from client [clientId: {}], cause:",
                            endpoint.clientIdentifier(), t);

                    final MqttConnectReturnCode code = getConnectReturnCode(t);
                    rejectConnectionRequest(endpoint, code, span);
                    TracingHelper.logError(span, t);
                }
                metrics.reportConnectionAttempt(
                        AbstractProtocolAdapterBase.getOutcome(t),
                        t instanceof ServiceInvocationException
                                ? ((ServiceInvocationException) t).getTenant()
                                : null,
                        cipherSuite);
            })
            .onComplete(result -> span.finish());

    }

    private Future<Device> handleConnectionRequest(final MqttEndpoint endpoint,
            final AtomicBoolean connectionClosedPrematurely, final Span currentSpan) {

        // the ConnectionLimitManager is null in some unit tests
        if (getConnectionLimitManager() != null && getConnectionLimitManager().isLimitExceeded()) {
            currentSpan.log("adapter's connection limit exceeded");
            return Future.failedFuture(new AdapterConnectionsExceededException(null, "adapter's connection limit is exceeded", null));
        }

        if (getConfig().isAuthenticationRequired()) {
            return handleEndpointConnectionWithAuthentication(endpoint, connectionClosedPrematurely, currentSpan);
        } else {
            return handleEndpointConnectionWithoutAuthentication(endpoint);
        }
    }

    private Future<Device> handleConnectionRequestResult(
            final MqttEndpoint endpoint,
            final Device authenticatedDevice,
            final AtomicBoolean connectionClosedPrematurely,
            final Span currentSpan) {

        TracingHelper.TAG_AUTHENTICATED.set(currentSpan, authenticatedDevice != null);
        if (authenticatedDevice != null) {
            TracingHelper.setDeviceTags(currentSpan, authenticatedDevice.getTenantId(),
                    authenticatedDevice.getDeviceId());
        }

        final Promise<Device> result = Promise.promise();

        if (connectionClosedPrematurely.get()) {
            log.debug("abort handling connection request, connection already closed [clientId: {}]",
                    endpoint.clientIdentifier());
            currentSpan.log("abort connection request processing, connection already closed");
            result.fail("connection already closed");
        } else {
            sendConnectedEvent(endpoint.clientIdentifier(), authenticatedDevice, currentSpan.context())
                .map(authenticatedDevice)
                .recover(t -> {
                    log.warn("failed to send connection event for client [clientId: {}]",
                            endpoint.clientIdentifier(), t);
                    return Future.failedFuture(new ServerErrorException(
                            HttpURLConnection.HTTP_UNAVAILABLE,
                            "failed to send connection event",
                            t));
                })
                .onComplete(result);
        }
        return result.future();
    }

    private void rejectConnectionRequest(final MqttEndpoint endpoint, final MqttConnectReturnCode errorCode,
            final Span currentSpan) {
        // MqttEndpointImpl.isClosed==true leads to an IllegalStateException here; try-catch block needed since there is no getter for that field
        try {
            endpoint.reject(errorCode);
            currentSpan.log("connection request rejected");
        } catch (final IllegalStateException ex) {
            if ("MQTT endpoint is closed".equals(ex.getMessage())) {
                log.debug("skipped rejecting connection request, connection already closed [clientId: {}]",
                        endpoint.clientIdentifier());
                currentSpan.log("skipped rejecting connection request, connection already closed");
            } else {
                log.debug("could not reject connection request from client [clientId: {}]: {}",
                        endpoint.clientIdentifier(), ex.toString());
                TracingHelper.logError(currentSpan, "could not reject connection request from client", ex);
            }
        }
    }

    /**
     * Invoked when a client sends its <em>CONNECT</em> packet and client authentication has been disabled by setting
     * the {@linkplain MqttProtocolAdapterProperties#isAuthenticationRequired() authentication required} configuration
     * property to {@code false}.
     * <p>
     * Registers a close handler on the endpoint which invokes {@link #onClose(MqttEndpoint)}.
     * Registers a publish handler on the endpoint which invokes {@link #onPublishedMessage(MqttContext)}
     * for each message being published by the client. Accepts the connection request.
     *
     * @param endpoint The MQTT endpoint representing the client.
     */
    private Future<Device> handleEndpointConnectionWithoutAuthentication(final MqttEndpoint endpoint) {

        registerEndpointHandlers(endpoint, null, OptionalInt.empty());
        metrics.incrementUnauthenticatedConnections();
        log.debug("unauthenticated device [clientId: {}] connected", endpoint.clientIdentifier());
        return Future.succeededFuture(null);
    }

    private Future<Device> handleEndpointConnectionWithAuthentication(final MqttEndpoint endpoint,
            final AtomicBoolean connectionClosedPrematurely, final Span currentSpan) {

        final MqttConnectContext context = MqttConnectContext.fromConnectPacket(endpoint, currentSpan);

        final Future<DeviceUser> authAttempt = authHandler.authenticateDevice(context);
        return authAttempt
                .compose(authenticatedDevice -> CompositeFuture.all(
                        getTenantConfiguration(authenticatedDevice.getTenantId(), currentSpan.context())
                                .compose(tenantObj -> CompositeFuture.all(
                                        isAdapterEnabled(tenantObj),
                                        checkConnectionLimit(tenantObj, currentSpan.context()))),
                        checkDeviceRegistration(authenticatedDevice, currentSpan.context()))
                        .map(authenticatedDevice))
                .compose(authenticatedDevice -> {
                    if (connectionClosedPrematurely.get()) { // check whether client disconnected during credentials validation
                        return Future.failedFuture("connection already closed");
                    }
                    registerEndpointHandlers(endpoint, authenticatedDevice, context.getTraceSamplingPriority());
                    metrics.incrementConnections(authenticatedDevice.getTenantId());
                    return Future.succeededFuture((Device) authenticatedDevice);
                })
                .recover(t -> {
                    if (authAttempt.failed()) {
                        log.debug("device authentication or early stage checks failed", t);
                    } else {
                        log.debug("cannot establish connection with device [tenant-id: {}, device-id: {}]",
                                authAttempt.result().getTenantId(), authAttempt.result().getDeviceId(), t);
                    }
                    return Future.failedFuture(t);
                });
    }

    /**
     * Forwards a message to the AMQP Messaging Network.
     *
     * @param ctx The context created for the MQTT message that is to be forwarded.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will succeed if the message has been forwarded successfully.
     *         Otherwise the future will fail with a {@link ServiceInvocationException}.
     * @throws NullPointerException if ctx is {@code null}.
     * @throws IllegalArgumentException if the context does not contain tenant/device information.
     */
    public final Future<Void> uploadMessage(final MqttContext ctx) {
        Objects.requireNonNull(ctx);
        verifyTenantAndDeviceContextIsSet(ctx);

        switch (ctx.endpoint()) {
        case TELEMETRY:
            return uploadTelemetryMessage(ctx);
        case EVENT:
            return uploadEventMessage(ctx);
        case COMMAND:
            return uploadCommandResponseMessage(ctx);
        default:
            return Future
                    .failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "unsupported endpoint"));
        }
    }

    private void verifyTenantAndDeviceContextIsSet(final MqttContext ctx) {
        if (ctx.tenant() == null || ctx.deviceId() == null) {
            throw new IllegalArgumentException("context does not contain tenant/device information");
        }
    }

    /**
     * Forwards a telemetry message to the AMQP Messaging Network.
     *
     * @param ctx The context in which the MQTT message has been published.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will succeed if the message has been forwarded successfully.
     *         Otherwise the future will fail with a {@link ServiceInvocationException}.
     * @throws NullPointerException if ctx is {@code null}.
     * @throws IllegalArgumentException if the context does not contain a telemetry message
     *         or tenant/device information is not set.
     */
    public final Future<Void> uploadTelemetryMessage(final MqttContext ctx) {
        Objects.requireNonNull(ctx);

        if (ctx.endpoint() != EndpointType.TELEMETRY) {
            throw new IllegalArgumentException("context does not contain telemetry message but " +
                ctx.endpoint().getCanonicalName());
        }
        verifyTenantAndDeviceContextIsSet(ctx);

        final Buffer payload = ctx.payload();
        final MetricsTags.QoS qos = MetricsTags.QoS.from(ctx.qosLevel().value());
        final Future<TenantObject> tenantTracker = getTenantConfiguration(ctx.tenant(), ctx.getTracingContext());

        return tenantTracker
                .compose(tenantObject -> uploadMessage(ctx, tenantObject, ctx.deviceId(), payload, ctx.endpoint()))
                .compose(success -> {
                    metrics.reportTelemetry(
                            ctx.endpoint(),
                            ctx.tenant(),
                            tenantTracker.result(),
                            MetricsTags.ProcessingOutcome.FORWARDED,
                            qos,
                            payload.length(),
                            ctx.getTimer());
                    return Future.<Void> succeededFuture();
                }).recover(t -> {
                    metrics.reportTelemetry(
                            ctx.endpoint(),
                            ctx.tenant(),
                            tenantTracker.result(),
                            ProcessingOutcome.from(t),
                            qos,
                            payload.length(),
                            ctx.getTimer());
                    return Future.failedFuture(t);
                });
    }

    /**
     * Forwards an event to the AMQP Messaging Network.
     *
     * @param ctx The context in which the MQTT message has been published.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will succeed if the message has been forwarded successfully.
     *         Otherwise the future will fail with a {@link ServiceInvocationException}.
     * @throws NullPointerException if any of ctx, tenant or deviceId is {@code null}.
     * @throws IllegalArgumentException if the context does not contain an event
     *         or tenant/device information is not set.
     */
    public final Future<Void> uploadEventMessage(final MqttContext ctx) {
        Objects.requireNonNull(ctx);

        if (ctx.endpoint() != EndpointType.EVENT) {
            throw new IllegalArgumentException("context does not contain event but " +
                ctx.endpoint().getCanonicalName());
        }
        verifyTenantAndDeviceContextIsSet(ctx);

        final Buffer payload = ctx.payload();
        final MetricsTags.QoS qos = MetricsTags.QoS.from(ctx.qosLevel().value());
        final Future<TenantObject> tenantTracker = getTenantConfiguration(ctx.tenant(), ctx.getTracingContext());

        return tenantTracker
                .compose(tenantObject -> uploadMessage(ctx, tenantObject, ctx.deviceId(), payload, ctx.endpoint()))
                .compose(success -> {
                    metrics.reportTelemetry(
                            ctx.endpoint(),
                            ctx.tenant(),
                            tenantTracker.result(),
                            MetricsTags.ProcessingOutcome.FORWARDED,
                            qos,
                            payload.length(),
                            ctx.getTimer());
                    return Future.<Void> succeededFuture();
                }).recover(t -> {
                    metrics.reportTelemetry(
                            ctx.endpoint(),
                            ctx.tenant(),
                            tenantTracker.result(),
                            ProcessingOutcome.from(t),
                            qos,
                            payload.length(),
                            ctx.getTimer());
                    return Future.failedFuture(t);
                });
    }

    /**
     * Uploads a command response message.
     *
     * @param ctx The context in which the MQTT message has been published.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will succeed if the message has been uploaded successfully.
     *         Otherwise, the future will fail with a {@link ServiceInvocationException}.
     * @throws NullPointerException if ctx is {@code null}.
     * @throws IllegalArgumentException if the context does not contain a command response
     *         or tenant/device information is not set.
     */
    public final Future<Void> uploadCommandResponseMessage(final MqttContext ctx) {
        Objects.requireNonNull(ctx);

        if (ctx.endpoint() != EndpointType.COMMAND) {
            throw new IllegalArgumentException("context does not contain command response message but " +
                    ctx.endpoint().getCanonicalName());
        }
        verifyTenantAndDeviceContextIsSet(ctx);

        final String[] addressPath = ctx.topic().getResourcePath();
        final String tenantId = ctx.tenant();
        final String deviceId = ctx.deviceId();
        Integer status = null;
        String reqId = null;

        final Future<CommandResponse> commandResponseTracker;
        if (addressPath.length <= CommandConstants.TOPIC_POSITION_RESPONSE_STATUS) {
            commandResponseTracker = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                    "command response topic has too few segments"));
        } else {
            try {
                status = Integer.parseInt(addressPath[CommandConstants.TOPIC_POSITION_RESPONSE_STATUS]);
            } catch (final NumberFormatException e) {
                log.trace("got invalid status code [{}] [tenant-id: {}, device-id: {}]",
                        addressPath[CommandConstants.TOPIC_POSITION_RESPONSE_STATUS], tenantId, deviceId);
            }
            if (status != null) {
                reqId = addressPath[CommandConstants.TOPIC_POSITION_RESPONSE_REQ_ID];
                final CommandResponse commandResponse = CommandResponse.fromRequestId(reqId, tenantId,
                        deviceId, ctx.payload(), ctx.contentType(), status);

                commandResponseTracker = commandResponse != null ? Future.succeededFuture(commandResponse)
                        : Future.failedFuture(new ClientErrorException(
                                HttpURLConnection.HTTP_BAD_REQUEST, "command response topic contains invalid data"));
            } else {
                // status code could not be parsed
                commandResponseTracker = Future.failedFuture(
                        new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "invalid status code"));
            }
        }

        final Span currentSpan = TracingHelper
                .buildChildSpan(tracer, ctx.getTracingContext(), "upload Command response", getTypeName())
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantId)
                .withTag(TracingHelper.TAG_DEVICE_ID, deviceId)
                .withTag(Constants.HEADER_COMMAND_RESPONSE_STATUS, status)
                .withTag(Constants.HEADER_COMMAND_REQUEST_ID, reqId)
                .withTag(TracingHelper.TAG_AUTHENTICATED.getKey(), ctx.authenticatedDevice() != null)
                .start();

        final int payloadSize = ctx.payload().length();
        final Future<TenantObject> tenantTracker = getTenantConfiguration(tenantId, ctx.getTracingContext());

        return CompositeFuture.all(tenantTracker, commandResponseTracker)
                .compose(success -> {
                    final Future<RegistrationAssertion> deviceRegistrationTracker = getRegistrationAssertion(
                            tenantId,
                            deviceId,
                            ctx.authenticatedDevice(),
                            currentSpan.context());
                    final Future<Void> tenantValidationTracker = CompositeFuture.all(
                            isAdapterEnabled(tenantTracker.result()),
                            checkMessageLimit(tenantTracker.result(), payloadSize, currentSpan.context()))
                            .mapEmpty();
                    return CompositeFuture.all(deviceRegistrationTracker, tenantValidationTracker)
                            .compose(ok -> sendCommandResponse(
                                    tenantTracker.result(),
                                    deviceRegistrationTracker.result(),
                                    commandResponseTracker.result(),
                                    currentSpan.context()));
                })
                .compose(delivery -> {
                    log.trace("successfully forwarded command response from device [tenant-id: {}, device-id: {}]",
                            tenantId, deviceId);
                    metrics.reportCommand(
                            Direction.RESPONSE,
                            tenantId,
                            tenantTracker.result(),
                            ProcessingOutcome.FORWARDED,
                            payloadSize,
                            ctx.getTimer());
                    // check that the remote MQTT client is still connected before sending PUBACK
                    if (ctx.isAtLeastOnce() && ctx.deviceEndpoint().isConnected()) {
                        currentSpan.log(EVENT_SENDING_PUBACK);
                        ctx.acknowledge();
                    }
                    currentSpan.finish();
                    return Future.<Void> succeededFuture();
                })
                .recover(t -> {
                    TracingHelper.logError(currentSpan, t);
                    currentSpan.finish();
                    metrics.reportCommand(
                            Direction.RESPONSE,
                            tenantId,
                            tenantTracker.result(),
                            ProcessingOutcome.from(t),
                            payloadSize,
                            ctx.getTimer());
                    return Future.failedFuture(t);
                });
    }

    private Future<Void> uploadMessage(
            final MqttContext ctx,
            final TenantObject tenantObject,
            final String deviceId,
            final Buffer payload,
            final MetricsTags.EndpointType endpoint) {

        if (!isPayloadOfIndicatedType(payload, ctx.contentType())) {
            return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                    String.format("Content-Type %s does not match payload", ctx.contentType())));
        }

        final Span currentSpan = TracingHelper.buildChildSpan(tracer, ctx.getTracingContext(),
                "upload " + endpoint, getTypeName())
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantObject.getTenantId())
                .withTag(TracingHelper.TAG_DEVICE_ID, deviceId)
                .withTag(TracingHelper.TAG_AUTHENTICATED.getKey(), ctx.authenticatedDevice() != null)
                .start();

        final Future<RegistrationAssertion> tokenTracker = getRegistrationAssertion(
                tenantObject.getTenantId(),
                deviceId,
                ctx.authenticatedDevice(),
                currentSpan.context());
        final Future<TenantObject> tenantValidationTracker = CompositeFuture.all(
                isAdapterEnabled(tenantObject),
                checkMessageLimit(tenantObject, payload.length(), currentSpan.context()))
            .map(tenantObject);

        return CompositeFuture.all(tokenTracker, tenantValidationTracker).compose(ok -> {

            final Map<String, Object> props = getDownstreamMessageProperties(ctx);
            props.put(MessageHelper.APP_PROPERTY_QOS, ctx.getRequestedQos().ordinal());
            addRetainAnnotation(ctx, props, currentSpan);
            customizeDownstreamMessageProperties(props, ctx);

            if (endpoint == EndpointType.EVENT) {
                return getEventSender(tenantObject).sendEvent(
                        tenantObject,
                        tokenTracker.result(),
                        ctx.contentType(),
                        payload,
                        props,
                        currentSpan.context());
            } else {
                return getTelemetrySender(tenantObject).sendTelemetry(
                        tenantObject,
                        tokenTracker.result(),
                        ctx.getRequestedQos(),
                        ctx.contentType(),
                        payload,
                        props,
                        currentSpan.context());
            }
        }).map(ok -> {

            log.trace("successfully processed message [topic: {}, QoS: {}] from device [tenantId: {}, deviceId: {}]",
                    ctx.getOrigAddress(), ctx.qosLevel(), tenantObject.getTenantId(), deviceId);
            // check that the remote MQTT client is still connected before sending PUBACK
            if (ctx.isAtLeastOnce() && ctx.deviceEndpoint().isConnected()) {
                currentSpan.log(EVENT_SENDING_PUBACK);
                ctx.acknowledge();
            }
            currentSpan.finish();
            return ok;

        }).recover(t -> {

            if (t instanceof ClientErrorException e) {
                log.debug("cannot process message [endpoint: {}] from device [tenantId: {}, deviceId: {}]: {} - {}",
                        endpoint, tenantObject.getTenantId(), deviceId, e.getErrorCode(), e.getMessage());
            } else {
                log.debug("cannot process message [endpoint: {}] from device [tenantId: {}, deviceId: {}]",
                        endpoint, tenantObject.getTenantId(), deviceId, t);
            }
            TracingHelper.logError(currentSpan, t);
            currentSpan.finish();
            return Future.failedFuture(t);
        });
    }

    private void onBeforeEndpointClose(final MqttDeviceEndpoint endpoint) {
        if (endpoint.getAuthenticatedDevice() != null) {
            connectedAuthenticatedDeviceEndpoints.remove(endpoint);
        }
    }

    /**
     * Invoked before the connection with a device is closed.
     * <p>
     * Subclasses should override this method in order to release any device specific resources.
     * <p>
     * This default implementation does nothing.
     *
     * @param endpoint The connection to be closed.
     */
    protected void onClose(final MqttEndpoint endpoint) {
    }

    /**
     * Processes an MQTT message that has been published by a device.
     * <p>
     * Subclasses should determine
     * <ul>
     * <li>the tenant and identifier of the device that has published the message</li>
     * <li>the payload to send downstream</li>
     * <li>the content type of the payload</li>
     * </ul>
     * and then invoke one of the <em>upload*</em> methods to send the message downstream.
     *
     * @param ctx The context in which the MQTT message has been published. The
     *            {@link MqttContext#topic()} method will return a non-null resource identifier
     *            for the topic that the message has been published to.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will succeed if the message has been successfully uploaded. Otherwise, the future will fail
     *         with a {@link ServiceInvocationException}.
     */
    protected abstract Future<Void> onPublishedMessage(MqttContext ctx);

    /**
     * Invoked before the message is sent to the downstream peer.
     * <p>
     * Subclasses may override this method in order to customize the
     * properties used for sending the message, e.g. adding custom properties.
     *
     * @param messageProperties The properties that are being added to the
     *                          downstream message.
     * @param ctx The message processing context.
     */
    protected void customizeDownstreamMessageProperties(final Map<String, Object> messageProperties, final MqttContext ctx) {
        // this default implementation does nothing
    }

    /**
     * Invoked when a message has been forwarded downstream successfully.
     * <p>
     * This default implementation does nothing.
     * <p>
     * Subclasses should override this method in order to e.g. update metrics counters.
     *
     * @param ctx The context in which the MQTT message has been published.
     */
    protected void onMessageSent(final MqttContext ctx) {
    }

    /**
     * Invoked when a message could not be forwarded downstream.
     * <p>
     * This method will only be invoked if the failure to forward the message has not been caused by the device that
     * published the message. In particular, this method will not be invoked for messages that cannot be authorized or
     * that are published to an unsupported/unrecognized topic. Such messages will be silently discarded.
     * <p>
     * This default implementation does nothing.
     * <p>
     * Subclasses should override this method in order to e.g. update metrics counters.
     *
     * @param ctx The context in which the MQTT message has been published.
     */
    protected void onMessageUndeliverable(final MqttContext ctx) {
    }

    private static void addRetainAnnotation(
            final MqttContext context,
            final Map<String, Object> props,
            final Span currentSpan) {

        if (context.isRetain()) {
            currentSpan.log("device wants to retain message");
            props.put(MessageHelper.ANNOTATION_X_OPT_RETAIN, Boolean.TRUE);
        }
    }

    private void registerEndpointHandlers(final MqttEndpoint endpoint, final Device authenticatedDevice,
            final OptionalInt traceSamplingPriority) {

        final MqttDeviceEndpoint deviceEndpoint = createMqttDeviceEndpoint(endpoint, authenticatedDevice,
                traceSamplingPriority);
        deviceEndpoint.registerHandlers();
    }

    final MqttDeviceEndpoint createMqttDeviceEndpoint(final MqttEndpoint endpoint, final Device authenticatedDevice,
            final OptionalInt traceSamplingPriority) {
        final MqttDeviceEndpoint mqttDeviceEndpoint = new MqttDeviceEndpoint(endpoint, authenticatedDevice,
                traceSamplingPriority);
        if (authenticatedDevice != null) {
            connectedAuthenticatedDeviceEndpoints.add(mqttDeviceEndpoint);
        }
        return mqttDeviceEndpoint;
    }

    private static MqttConnectReturnCode getConnectReturnCode(final Throwable e) {

        if (e instanceof MqttConnectionException) {
            return ((MqttConnectionException) e).code();
        } else if (e instanceof AuthorizationException) {
            if (e instanceof AdapterConnectionsExceededException) {
                return MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE;
            } else {
                return MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
            }
        } else if (e instanceof ServiceInvocationException) {
            switch (((ServiceInvocationException) e).getErrorCode()) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
            case HttpURLConnection.HTTP_NOT_FOUND:
                return MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
            case HttpURLConnection.HTTP_UNAVAILABLE:
                return MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE;
            default:
                return MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
            }
        } else {
            return MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
        }
    }

    /**
     * The endpoint representing a connected MQTT device.
     */
    public class MqttDeviceEndpoint {

        private final MqttEndpoint endpoint;
        /**
         * The authenticated identity of the device or {@code null} if the device has not been authenticated.
         */
        private final Device authenticatedDevice;
        private final OptionalInt traceSamplingPriority;
        private final Map<Subscription.Key, Pair<CommandSubscription, CommandConsumer>> commandSubscriptions = new ConcurrentHashMap<>();
        private final Map<Subscription.Key, ErrorSubscription> errorSubscriptions = new HashMap<>();
        private final PendingPubAcks pendingAcks = new PendingPubAcks(vertx);

        private Throwable protocolLevelException;

        /**
         * Creates a new MqttDeviceEndpoint.
         *
         * @param endpoint The endpoint representing the connection to the device.
         * @param authenticatedDevice The authenticated identity of the device or {@code null} if the device has not been authenticated.
         * @param traceSamplingPriority The sampling priority to be applied on the <em>OpenTracing</em> spans
         *                              created in the context of this endpoint.
         * @throws NullPointerException if endpoint or traceSamplingPriority is {@code null}.
         */
        public MqttDeviceEndpoint(final MqttEndpoint endpoint, final Device authenticatedDevice, final OptionalInt traceSamplingPriority) {
            this.endpoint = Objects.requireNonNull(endpoint);
            this.authenticatedDevice = authenticatedDevice;
            this.traceSamplingPriority = Objects.requireNonNull(traceSamplingPriority);
        }

        /**
         * Gets the authenticated device.
         *
         * @return The authenticated device or {@code null}.
         */
        protected final Device getAuthenticatedDevice() {
            return authenticatedDevice;
        }

        /**
         * Registers the handlers on the contained MqttEndpoint.
         */
        protected final void registerHandlers() {
            endpoint.publishHandler(this::handlePublishedMessage);
            endpoint.publishAcknowledgeHandler(this::handlePubAck);
            endpoint.subscribeHandler(this::onSubscribe);
            endpoint.unsubscribeHandler(this::onUnsubscribe);
            endpoint.closeHandler(v -> onClose());
            endpoint.exceptionHandler(this::onProtocolLevelError);
        }

        /**
         * To be called by the MqttEndpoint exceptionHandler. The given exception could for example be a
         * {@code io.netty.handler.codec.TooLongFrameException} if the MQTT max message size was exceeded.
         * The MQTT server will close the connection on such an error, triggering a subsequent invocation of
         * the endpoint closeHandler.
         *
         * @param throwable The exception.
         */
        private void onProtocolLevelError(final Throwable throwable) {
            if (authenticatedDevice == null) {
                log.debug("protocol-level exception occurred [client ID: {}]", endpoint.clientIdentifier(), throwable);
            } else {
                log.debug("protocol-level exception occurred [tenant-id: {}, device-id: {}, client ID: {}]",
                        authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId(),
                        endpoint.clientIdentifier(), throwable);
            }
            this.protocolLevelException = throwable;
        }

        /**
         * Invoked when a device sends an MQTT <em>PUBLISH</em> packet.
         *
         * @param message The message received from the device.
         * @throws NullPointerException if message is {@code null}.
         */
        protected final void handlePublishedMessage(final MqttPublishMessage message) {
            Objects.requireNonNull(message);
            // try to extract a SpanContext from the property bag of the message's topic (if set)
            final SpanContext spanContext = Optional.ofNullable(message.topicName())
                    .flatMap(topic -> Optional.ofNullable(PropertyBag.fromTopic(message.topicName())))
                    .map(propertyBag -> TracingHelper.extractSpanContext(tracer, propertyBag::getPropertiesIterator))
                    .orElse(null);
            final Span span = newChildSpan(spanContext, "PUBLISH");
            span.setTag(Tags.MESSAGE_BUS_DESTINATION.getKey(), message.topicName());
            span.setTag(TracingHelper.TAG_QOS.getKey(), message.qosLevel().toString());
            traceSamplingPriority.ifPresent(prio -> TracingHelper.setTraceSamplingPriority(span, prio));

            final MqttContext context = MqttContext.fromPublishPacket(message, endpoint, span, authenticatedDevice);
            context.setTimer(getMetrics().startTimer());

            final Future<Void> spanPreparationFuture = authenticatedDevice == null
                    ? applyTraceSamplingPriorityForTopicTenant(context.topic(), span)
                    : Future.succeededFuture();

            spanPreparationFuture
                    .compose(v -> checkTopic(context))
                    .compose(ok -> onPublishedMessage(context))
                    .onSuccess(ok -> {
                        Tags.HTTP_STATUS.set(span, HttpURLConnection.HTTP_ACCEPTED);
                        onMessageSent(context);
                        span.finish();
                    })
                    .onFailure(error -> handlePublishedMessageError(context, error, span));
        }

        private void handlePublishedMessageError(final MqttContext context, final Throwable error, final Span span) {
            final ErrorSubscription errorSubscription = getErrorSubscription(context);
            final Future<Void> errorSentIfNeededFuture = Optional.ofNullable(errorSubscription)
                    .map(v -> publishError(errorSubscription, context, error, span.context()))
                    .orElseGet(Future::succeededFuture);

            Tags.HTTP_STATUS.set(span, ServiceInvocationException.extractStatusCode(error));
            TracingHelper.logError(span, error);

            if (!(error instanceof ClientErrorException)) {
                onMessageUndeliverable(context);
            }

            errorSentIfNeededFuture
                    .compose(ok -> isTerminalError(error, context.deviceId(), authenticatedDevice, span.context()))
                    .onComplete(ar -> {
                        final boolean isTerminalError = ar.succeeded() ? ar.result() : false;
                        final ErrorHandlingMode errorHandlingMode = context
                                .getErrorHandlingMode(errorSubscription != null);

                        if (errorHandlingMode == ErrorHandlingMode.DISCONNECT || isTerminalError) {
                            if (context.deviceEndpoint().isConnected()) {
                                span.log("closing connection to device");
                                context.deviceEndpoint().close();
                            }
                        } else if (context.isAtLeastOnce()) {
                            if (errorHandlingMode == ErrorHandlingMode.SKIP_ACK) {
                                span.log("skipped sending PUBACK");
                            } else if (context.deviceEndpoint().isConnected()) {
                                span.log(EVENT_SENDING_PUBACK);
                                context.acknowledge();
                            }
                        }
                        span.finish();
                    });
        }

        /**
         * Applies the trace sampling priority configured for the tenant derived from the given topic on the given span.
         * <p>
         * This is for unauthenticated MQTT connections where the tenant id gets taken from the message topic.
         *
         * @param topic The topic (may be {@code null}).
         * @param span The <em>OpenTracing</em> span to apply the configuration to.
         * @return A succeeded future indicating the outcome of the operation. A failure to determine the tenant is ignored
         *         here.
         * @throws NullPointerException if span is {@code null}.
         */
        protected final Future<Void> applyTraceSamplingPriorityForTopicTenant(final ResourceIdentifier topic, final Span span) {
            Objects.requireNonNull(span);
            if (topic == null || topic.getTenantId() == null) {
                // an invalid address is ignored here
                return Future.succeededFuture();
            }
            return getTenantConfiguration(topic.getTenantId(), span.context())
                    .map(tenantObject -> {
                        TracingHelper.setDeviceTags(span, tenantObject.getTenantId(), null);
                        TenantTraceSamplingHelper.applyTraceSamplingPriority(tenantObject, null, span);
                        return (Void) null;
                    })
                    .recover(t -> Future.succeededFuture());
        }

        private Future<Void> checkTopic(final MqttContext context) {
            if (context.topic() == null) {
                return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "malformed topic name"));
            } else {
                return Future.succeededFuture();
            }
        }

        private ErrorSubscription getErrorSubscription(final MqttContext context) {
            ErrorSubscription result = null;
            if (context.tenant() != null && context.deviceId() != null) {
                // first check for a subscription with the device id taken from the topic or the authenticated device
                // (handles case where a gateway is publishing for specific edge devices and has individual error subscriptions for them)
                result = errorSubscriptions.get(ErrorSubscription.getKey(context.tenant(), context.deviceId()));
            }
            if (result == null && context.authenticatedDevice() != null) {
                // otherwise check using the authenticated device (ie. potentially gateway) id
                result = errorSubscriptions
                        .get(ErrorSubscription.getKey(context.tenant(), context.authenticatedDevice().getDeviceId()));
            }
            return result;
        }

        /**
         * Invoked when a device sends an MQTT <em>PUBACK</em> packet.
         *
         * @param msgId The message/packet id.
         * @throws NullPointerException if msgId is {@code null}.
         */
        public final void handlePubAck(final Integer msgId) {
            Objects.requireNonNull(msgId);
            pendingAcks.handlePubAck(msgId);
        }

        /**
         * Invoked when a device sends an MQTT <em>SUBSCRIBE</em> packet.
         * <p>
         * This method supports topic filters for subscribing to commands
         * and error messages as defined by Hono's
         * <a href="https://www.eclipse.org/hono/docs/user-guide/mqtt-adapter/#command-control">
         * MQTT adapter user guide</a>.
         * <p>
         * When a device subscribes to a command topic filter, this method opens a
         * command consumer for receiving commands from applications for the device and
         * sends an empty notification downstream, indicating that the device will be
         * ready to receive commands until further notice.
         *
         * @param subscribeMsg The subscribe request received from the device.
         * @throws NullPointerException if subscribeMsg is {@code null}.
         */
        protected final void onSubscribe(final MqttSubscribeMessage subscribeMsg) {
            Objects.requireNonNull(subscribeMsg);
            final Map<Subscription.Key, Future<Subscription>> uniqueSubscriptions = new HashMap<>();
            final Deque<Future<Subscription>> subscriptionOutcomes = new ArrayDeque<>(subscribeMsg.topicSubscriptions().size());

            final Span span = newSpan("SUBSCRIBE");

            // process in reverse order and skip equivalent subscriptions, meaning the last of such subscriptions shall be used
            // (done with respect to MQTT 3.1.1 spec section 3.8.4, processing multiple filters as if they had been submitted
            //  using multiple separate SUBSCRIBE packets)
            final Deque<MqttTopicSubscription> topicSubscriptions = new LinkedList<>(subscribeMsg.topicSubscriptions());
            topicSubscriptions.descendingIterator().forEachRemaining(mqttTopicSub -> {

                final Future<Subscription> result;
                final Subscription sub = CommandSubscription.hasCommandEndpointPrefix(mqttTopicSub.topicName())
                        ? CommandSubscription.fromTopic(mqttTopicSub, authenticatedDevice)
                        : ErrorSubscription.fromTopic(mqttTopicSub, authenticatedDevice);

                if (sub == null) {
                    TracingHelper.logError(span, String.format("unsupported topic filter [%s]", mqttTopicSub.topicName()));
                    log.debug("cannot create subscription [filter: {}, requested QoS: {}]: unsupported topic filter",
                            mqttTopicSub.topicName(), mqttTopicSub.qualityOfService());
                    result = Future.failedFuture(new IllegalArgumentException("unsupported topic filter"));
                } else {
                    if (uniqueSubscriptions.containsKey(sub.getKey())) {
                        final Map<String, Object> items = new HashMap<>(3);
                        items.put(Fields.EVENT, "ignoring duplicate subscription");
                        items.put(LOG_FIELD_TOPIC_FILTER, sub.getTopic());
                        items.put("requested QoS", sub.getQos());
                        span.log(items);
                        result = uniqueSubscriptions.get(sub.getKey());
                    } else {
                        result = registerSubscription(sub, span);
                        uniqueSubscriptions.put(sub.getKey(), result);
                    }
                }
                subscriptionOutcomes.addFirst(result); // add first to get the same order as in the SUBSCRIBE packet
            });

            // wait for all futures to complete before sending SUBACK
            CompositeFuture.join(new ArrayList<>(subscriptionOutcomes)).onComplete(v -> {

                if (endpoint.isConnected()) {
                    // return a status code for each topic filter contained in the SUBSCRIBE packet
                    final List<MqttQoS> grantedQosLevels = subscriptionOutcomes.stream()
                            .map(future -> future.failed() ? MqttQoS.FAILURE : future.result().getQos())
                            .collect(Collectors.toList());
                    endpoint.subscribeAcknowledge(subscribeMsg.messageId(), grantedQosLevels);

                    // now that we have informed the device about the outcome,
                    // we can send empty notifications for succeeded (distinct) command subscriptions downstream
                    subscriptionOutcomes.stream()
                            .filter(subFuture -> subFuture.succeeded() && subFuture.result() instanceof CommandSubscription)
                            .map(subFuture -> ((CommandSubscription) subFuture.result()).getKey())
                            .distinct()
                            .forEach(cmdSubKey -> {
                                sendConnectedTtdEvent(cmdSubKey.getTenant(), cmdSubKey.getDeviceId(), authenticatedDevice, span.context());
                            });
                } else {
                    TracingHelper.logError(span, "skipped sending command subscription notification - endpoint not connected anymore");
                    log.debug("skipped sending command subscription notification - endpoint not connected anymore [tenant-id: {}, device-id: {}]",
                            Optional.ofNullable(authenticatedDevice).map(Device::getTenantId).orElse(""),
                            Optional.ofNullable(authenticatedDevice).map(Device::getDeviceId).orElse(""));
                }
                span.finish();
            });
        }

        private Future<Subscription> registerSubscription(final Subscription sub, final Span span) {
            return (sub instanceof CommandSubscription)
                    ? registerCommandSubscription((CommandSubscription) sub, span)
                    : registerErrorSubscription((ErrorSubscription) sub, span);
        }

        private Future<Subscription> registerCommandSubscription(final CommandSubscription cmdSub, final Span span) {

            if (MqttQoS.EXACTLY_ONCE.equals(cmdSub.getQos())) {
                TracingHelper.logError(span, String.format("topic filter [%s] with unsupported QoS 2", cmdSub.getTopic()));
                return Future.failedFuture(new IllegalArgumentException("QoS 2 not supported for command subscription"));
            }
            return createCommandConsumer(cmdSub, span)
                    .map(consumer -> {
                        cmdSub.logSubscribeSuccess(span);
                        log.debug("created subscription [tenant: {}, device: {}, filter: {}, QoS: {}]",
                                cmdSub.getTenant(), cmdSub.getDeviceId(), cmdSub.getTopic(), cmdSub.getQos());
                        final Pair<CommandSubscription, CommandConsumer> existingCmdSub = commandSubscriptions.get(cmdSub.getKey());
                        if (existingCmdSub != null) {
                            span.log(String.format("subscription replaces previous subscription [QoS %s, filter %s]",
                                    existingCmdSub.one().getQos(), existingCmdSub.one().getTopic()));
                            log.debug("previous subscription [QoS {}, filter {}] is getting replaced",
                                    existingCmdSub.one().getQos(), existingCmdSub.one().getTopic());
                        }
                        commandSubscriptions.put(cmdSub.getKey(), Pair.of(cmdSub, consumer));
                        return (Subscription) cmdSub;
                    }).recover(t -> {
                        cmdSub.logSubscribeFailure(span, t);
                        log.debug("cannot create subscription [tenant: {}, device: {}, filter: {}, requested QoS: {}]",
                                cmdSub.getTenant(), cmdSub.getDeviceId(), cmdSub.getTopic(), cmdSub.getQos(), t);
                        return Future.failedFuture(t);
                    });
        }

        private Future<Subscription> registerErrorSubscription(final ErrorSubscription errorSub, final Span span) {

            if (!MqttQoS.AT_MOST_ONCE.equals(errorSub.getQos())) {
                TracingHelper.logError(span, String.format("topic filter [%s] with unsupported QoS %d", errorSub.getTopic(), errorSub.getQos().value()));
                return Future.failedFuture(new IllegalArgumentException(String.format("QoS %d not supported for error subscription", errorSub.getQos().value())));
            }
            return Future.succeededFuture()
                    .compose(v -> {
                        // in case of a gateway having subscribed for a specific device,
                        // check the via-gateways, ensuring that the gateway may act on behalf of the device at this point in time
                        if (errorSub.isGatewaySubscriptionForSpecificDevice()) {
                            return getRegistrationAssertion(
                                    authenticatedDevice.getTenantId(),
                                    errorSub.getDeviceId(),
                                    authenticatedDevice,
                                    span.context());
                        }
                        return Future.succeededFuture();
                    }).recover(t -> {
                        errorSub.logSubscribeFailure(span, t);
                        log.debug("cannot create subscription [tenant: {}, device: {}, filter: {}, requested QoS: {}]",
                                errorSub.getTenant(), errorSub.getDeviceId(), errorSub.getTopic(), errorSub.getQos(), t);
                        return Future.failedFuture(t);
                    }).compose(v -> {
                        errorSubscriptions.put(errorSub.getKey(), errorSub);
                        errorSub.logSubscribeSuccess(span);
                        log.debug("created subscription [tenant: {}, device: {}, filter: {}, QoS: {}]",
                                errorSub.getTenant(), errorSub.getDeviceId(), errorSub.getTopic(), errorSub.getQos());
                        return Future.succeededFuture(errorSub);
                    });
        }

        /**
         * Publishes an error message to the device.
         * <p>
         * Used for an error that occurred in the context of the given MqttContext,
         * while processing an MQTT message published by a device.
         *
         * @param subscription The device's command subscription.
         * @param context The context in which the error occurred.
         * @param error The error exception.
         * @param spanContext The span context.
         * @return A future indicating the outcome of the operation.
         * @throws NullPointerException if any of the parameters except spanContext is {@code null}.
         */
        protected final Future<Void> publishError(
                final ErrorSubscription subscription,
                final MqttContext context,
                final Throwable error,
                final SpanContext spanContext) {

            Objects.requireNonNull(subscription);
            Objects.requireNonNull(context);
            Objects.requireNonNull(error);

            final Span span = newChildSpan(spanContext, "publish error to device");

            final int errorCode = ServiceInvocationException.extractStatusCode(error);
            final String correlationId = Optional.ofNullable(context.correlationId()).orElse("-1");
            final String publishTopic = subscription.getErrorPublishTopic(context, errorCode);
            Tags.MESSAGE_BUS_DESTINATION.set(span, publishTopic);
            TracingHelper.TAG_QOS.set(span, subscription.getQos().name());

            final JsonObject errorJson = new JsonObject();
            errorJson.put("code", errorCode);
            errorJson.put("message", ServiceInvocationException.getErrorMessageForExternalClient(error));
            errorJson.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
                    .format(DateTimeFormatter.ISO_INSTANT));
            errorJson.put(MessageHelper.SYS_PROPERTY_CORRELATION_ID, correlationId);

            final String targetInfo = authenticatedDevice != null && !authenticatedDevice.getDeviceId().equals(context.deviceId())
                    ? String.format("gateway [%s], device [%s]", authenticatedDevice.getDeviceId(), context.deviceId())
                    : String.format("device [%s]", context.deviceId());

            return publish(publishTopic, errorJson.toBuffer(), subscription.getQos())
                    .onSuccess(msgId -> {
                        log.debug("published error message [packet-id: {}] to {} [tenant-id: {}, MQTT client-id: {}, QoS: {}, topic: {}]",
                                msgId, targetInfo, subscription.getTenant(), endpoint.clientIdentifier(),
                                subscription.getQos(), publishTopic);
                        span.log(subscription.getQos().value() > 0 ? "published error message, packet-id: " + msgId
                                : "published error message");
                    }).onFailure(thr -> {
                        log.debug("error publishing error message to {} [tenant-id: {}, MQTT client-id: {}, QoS: {}, topic: {}]",
                                targetInfo, subscription.getTenant(), endpoint.clientIdentifier(),
                                subscription.getQos(), publishTopic, thr);
                        TracingHelper.logError(span, "failed to publish error message", thr);
                    }).map(msgId -> (Void) null)
                    .onComplete(ar -> span.finish());
        }

        private Future<CommandConsumer> createCommandConsumer(final CommandSubscription subscription, final Span span) {

            final Function<CommandContext, Future<Void>> commandHandler = commandContext -> {

                Tags.COMPONENT.set(commandContext.getTracingSpan(), getTypeName());
                TracingHelper.TAG_CLIENT_ID.set(commandContext.getTracingSpan(), endpoint.clientIdentifier());
                final Sample timer = metrics.startTimer();
                addMicrometerSample(commandContext, timer);
                final Command command = commandContext.getCommand();
                final Future<TenantObject> tenantTracker = getTenantConfiguration(subscription.getTenant(), commandContext.getTracingContext());

                return tenantTracker.compose(tenantObject -> {
                    if (!command.isValid()) {
                        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                                "malformed command message"));
                    }
                    return checkMessageLimit(tenantObject, command.getPayloadSize(), commandContext.getTracingContext());
                }).compose(success -> {
                    // in case of a gateway having subscribed for a specific device,
                    // check the via-gateways, ensuring that the gateway may act on behalf of the device at this point in time
                    if (subscription.isGatewaySubscriptionForSpecificDevice()) {
                        return getRegistrationAssertion(
                                authenticatedDevice.getTenantId(),
                                subscription.getDeviceId(),
                                authenticatedDevice,
                                commandContext.getTracingContext());
                    }
                    return Future.succeededFuture();
                }).onFailure(t -> {
                    if (t instanceof ClientErrorException) {
                        commandContext.reject(t);
                    } else {
                        commandContext.release(t);
                    }
                    metrics.reportCommand(
                            command.isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                            subscription.getTenant(),
                            tenantTracker.result(),
                            ProcessingOutcome.from(t),
                            command.getPayloadSize(),
                            timer);
                }).compose(success -> onCommandReceived(tenantTracker.result(), subscription, commandContext));
            };

            final Future<RegistrationAssertion> tokenTracker = Optional.ofNullable(authenticatedDevice)
                    .map(v -> getRegistrationAssertion(
                            authenticatedDevice.getTenantId(),
                            subscription.getDeviceId(),
                            authenticatedDevice,
                            span.context()))
                    .orElseGet(Future::succeededFuture);

            if (subscription.isGatewaySubscriptionForSpecificDevice()) {
                // gateway scenario
                return tokenTracker.compose(v -> getCommandConsumerFactory().createCommandConsumer(
                        subscription.getTenant(),
                        subscription.getDeviceId(),
                        subscription.getAuthenticatedDeviceId(),
                        commandHandler,
                        null,
                        span.context()));
            } else {
                return tokenTracker.compose(v -> getCommandConsumerFactory().createCommandConsumer(
                        subscription.getTenant(),
                        subscription.getDeviceId(),
                        commandHandler,
                        null,
                        span.context()));
            }
        }

        /**
         * Handles a valid command that has been received from an application, forwarding it to the device.
         * <p>
         * Note that this method invokes one of the terminal methods of the passed in {@link CommandContext} in order to
         * settle the command message transfer and finish the trace span associated with the {@link CommandContext}.
         *
         * @param tenantObject The tenant configuration object.
         * @param subscription The device's command subscription.
         * @param commandContext The command to be delivered.
         * @return A future indicating the outcome of the operation. The future will be succeeded if the command has
         *         been sent to the device, otherwise it will be failed.
         * @throws NullPointerException if any of the parameters are {@code null}.
         */
        protected final Future<Void> onCommandReceived(
                final TenantObject tenantObject,
                final CommandSubscription subscription,
                final CommandContext commandContext) {

            Objects.requireNonNull(tenantObject);
            Objects.requireNonNull(subscription);
            Objects.requireNonNull(commandContext);

            final Command command = commandContext.getCommand();
            final String publishTopic = subscription.getCommandPublishTopic(command);
            Tags.MESSAGE_BUS_DESTINATION.set(commandContext.getTracingSpan(), publishTopic);
            TracingHelper.TAG_QOS.set(commandContext.getTracingSpan(), subscription.getQos().name());

            final String targetInfo = command.isTargetedAtGateway()
                ? String.format("gateway [%s], device [%s]", command.getGatewayId(), command.getDeviceId())
                : String.format("device [%s]", command.getDeviceId());

            return getCommandPayload(commandContext)
                    .map(mappedPayload -> Optional.ofNullable(mappedPayload).orElseGet(Buffer::buffer))
                    .onSuccess(payload -> {
                        // don't wait for the PUBACK from the device in order to complete the onCommandReceived result future here
                        publish(publishTopic, payload, subscription.getQos())
                                .onSuccess(msgId -> {
                                    log.debug("published command [packet-id: {}] to {} [tenant-id: {}, MQTT client-id: {}, QoS: {}, topic: {}]",
                                            msgId, targetInfo, subscription.getTenant(), endpoint.clientIdentifier(),
                                            subscription.getQos(), publishTopic);
                                    commandContext.getTracingSpan()
                                            .log(subscription.getQos().value() > 0
                                                    ? "published command, packet-id: " + msgId
                                                    : "published command");
                                    afterCommandPublished(msgId, commandContext, tenantObject, subscription);
                                })
                                .onFailure(thr -> {
                                    log.debug("error publishing command to {} [tenant-id: {}, MQTT client-id: {}, QoS: {}, topic: {}]",
                                            targetInfo, subscription.getTenant(), endpoint.clientIdentifier(),
                                            subscription.getQos(), publishTopic, thr);
                                    TracingHelper.logError(commandContext.getTracingSpan(), "failed to publish command", thr);
                                    reportPublishedCommand(
                                            tenantObject,
                                            subscription,
                                            commandContext,
                                            ProcessingOutcome.from(thr));
                                    commandContext.release(thr);
                                });
                    }).onFailure(t -> {
                        log.debug("error mapping command [tenant-id: {}, MQTT client-id: {}, QoS: {}]",
                                subscription.getTenant(), endpoint.clientIdentifier(), subscription.getQos(), t);
                        TracingHelper.logError(commandContext.getTracingSpan(), "failed to map command", t);
                        reportPublishedCommand(
                                tenantObject,
                                subscription,
                                commandContext,
                                ProcessingOutcome.from(t));
                        commandContext.release(t);
                    }).mapEmpty();
        }

        private Future<Integer> publish(final String topic, final Buffer payload, final MqttQoS qosLevel) {
            final Promise<Integer> publishSentPromise = Promise.promise();
            try {
                endpoint.publish(topic, payload, qosLevel, false, false, publishSentPromise);
            } catch (final Exception e) {
                publishSentPromise.fail(!endpoint.isConnected()
                        ? new ServerErrorException(HttpURLConnection.HTTP_UNAVAILABLE, "connection to device already closed")
                        : new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, e));
            }
            return publishSentPromise.future();
        }

        private void afterCommandPublished(
                final Integer publishedMsgId,
                final CommandContext commandContext,
                final TenantObject tenantObject,
                final CommandSubscription subscription) {

            final boolean requiresPuback = MqttQoS.AT_LEAST_ONCE.equals(subscription.getQos());

            if (requiresPuback) {
                final Handler<Integer> onAckHandler = msgId -> {
                    reportPublishedCommand(tenantObject, subscription, commandContext, ProcessingOutcome.FORWARDED);
                    log.debug("received PUBACK [packet-id: {}] for command [tenant-id: {}, device-id: {}, MQTT client-id: {}]",
                            msgId, subscription.getTenant(), subscription.getDeviceId(), endpoint.clientIdentifier());
                    commandContext.getTracingSpan().log("received PUBACK from device");
                    commandContext.accept();
                };

                final Handler<Void> onAckTimeoutHandler = v -> {
                    log.debug("did not receive PUBACK [packet-id: {}] for command [tenant-id: {}, device-id: {}, MQTT client-id: {}]",
                            publishedMsgId, subscription.getTenant(), subscription.getDeviceId(), endpoint.clientIdentifier());
                    commandContext.release(new ServerErrorException(HttpURLConnection.HTTP_UNAVAILABLE,
                            "did not receive PUBACK from device"));
                    reportPublishedCommand(tenantObject, subscription, commandContext, ProcessingOutcome.UNDELIVERABLE);
                };
                pendingAcks.add(publishedMsgId, onAckHandler, onAckTimeoutHandler, getConfig().getSendMessageToDeviceTimeout());
            } else {
                reportPublishedCommand(tenantObject, subscription, commandContext, ProcessingOutcome.FORWARDED);
                commandContext.accept();
            }
        }

        private void reportPublishedCommand(
                final TenantObject tenantObject,
                final CommandSubscription subscription,
                final CommandContext commandContext,
                final ProcessingOutcome outcome) {

            metrics.reportCommand(
                    commandContext.getCommand().isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                    subscription.getTenant(),
                    tenantObject,
                    outcome,
                    commandContext.getCommand().getPayloadSize(),
                    getMicrometerSample(commandContext));
        }

        /**
         * Invoked when a device sends an MQTT <em>UNSUBSCRIBE</em> packet.
         * <p>
         * This method currently only supports topic filters for unsubscribing from
         * commands as defined by Hono's
         * <a href="https://www.eclipse.org/hono/docs/user-guide/mqtt-adapter/#command-control">
         * MQTT adapter user guide</a>.
         *
         * @param unsubscribeMsg The unsubscribe request received from the device.
         * @throws NullPointerException if unsubscribeMsg is {@code null}.
         */
        protected final void onUnsubscribe(final MqttUnsubscribeMessage unsubscribeMsg) {
            Objects.requireNonNull(unsubscribeMsg);
            final Span span = newSpan("UNSUBSCRIBE");

            @SuppressWarnings("rawtypes")
            final List<Future> removalDoneFutures = new ArrayList<>(unsubscribeMsg.topics().size());
            unsubscribeMsg.topics().forEach(topic -> {

                final AtomicReference<Subscription> removedSubscription = new AtomicReference<>();
                if (CommandSubscription.hasCommandEndpointPrefix(topic)) {
                    Optional.ofNullable(CommandSubscription.getKey(topic, authenticatedDevice))
                            .map(commandSubscriptions::remove)
                            .ifPresent(subscriptionCommandConsumerPair -> {
                                final CommandSubscription subscription = subscriptionCommandConsumerPair.one();
                                removedSubscription.set(subscription);
                                subscription.logUnsubscribe(span);
                                removalDoneFutures.add(
                                        onCommandSubscriptionRemoved(subscriptionCommandConsumerPair, span, true));
                            });
                } else if (ErrorSubscription.hasErrorEndpointPrefix(topic)) {
                    Optional.ofNullable(ErrorSubscription.getKey(topic, authenticatedDevice))
                            .map(errorSubscriptions::remove)
                            .ifPresent(subscription -> {
                                removedSubscription.set(subscription);
                                subscription.logUnsubscribe(span);
                            });
                }
                if (removedSubscription.get() != null) {
                    log.debug("removed subscription with topic [{}] for device [tenant-id: {}, device-id: {}]",
                            topic, removedSubscription.get().getTenant(), removedSubscription.get().getDeviceId());
                } else {
                    TracingHelper.logError(span, String.format("no subscription found for topic filter [%s]", topic));
                    log.debug("cannot unsubscribe - no subscription found for topic filter [{}]", topic);
                }
            });
            if (endpoint.isConnected()) {
                endpoint.unsubscribeAcknowledge(unsubscribeMsg.messageId());
            }
            CompositeFuture.join(removalDoneFutures).onComplete(r -> span.finish());
        }

        private Future<Void> onCommandSubscriptionRemoved(
                final Pair<CommandSubscription, CommandConsumer> subscriptionConsumerPair, 
                final Span span, 
                final boolean sendDisconnectedEvent) {

            final CommandSubscription subscription = subscriptionConsumerPair.one();
            final CommandConsumer commandConsumer = subscriptionConsumerPair.two();
            return commandConsumer.close(span.context())
                    .recover(thr -> {
                        TracingHelper.logError(span, thr);
                        // ignore all but precon-failed errors
                        if (ServiceInvocationException.extractStatusCode(thr) == HttpURLConnection.HTTP_PRECON_FAILED) {
                            log.debug("command consumer wasn't active anymore - skip sending disconnected event [tenant: {}, device-id: {}]",
                                    subscription.getTenant(), subscription.getDeviceId());
                            span.log("command consumer wasn't active anymore - skip sending disconnected event");
                            return Future.failedFuture(thr);
                        }
                        return Future.succeededFuture();
                    })
                    .compose(v -> {
                        if (sendDisconnectedEvent) {
                            return sendDisconnectedTtdEvent(subscription.getTenant(), subscription.getDeviceId(), span);
                        }
                        return Future.succeededFuture();
                    });
        }

        private Future<Void> sendDisconnectedTtdEvent(final String tenant, final String device, final Span span) {
            final Span sendEventSpan = newChildSpan(span.context(), "send Disconnected Event");
            return AbstractVertxBasedMqttProtocolAdapter.this.sendDisconnectedTtdEvent(tenant, device, authenticatedDevice, sendEventSpan.context())
                    .onComplete(r -> sendEventSpan.finish()).mapEmpty();
        }

        private boolean stopCalled() {
            return stopResultPromiseRef.get() != null;
        }

        /**
         * Actively closes the connection to the device.
         *
         * @param reason The reason for closing the connection.
         * @param sendDisconnectedEvent {@code true} if events shall be sent concerning the disconnect.
         * @throws NullPointerException if reason is {@code null}.
         */
        protected final void close(final String reason, final boolean sendDisconnectedEvent) {
            Objects.requireNonNull(reason);

            final Span span = newSpan("close device connection");
            endpoint.closeHandler(v -> {});
            onCloseInternal(span, reason, sendDisconnectedEvent)
                    .onComplete(ar -> span.finish());
        }

        /**
         * To be called by the MqttEndpoint closeHandler.
         */
        protected final void onClose() {
            final Span span;
            final String reason;
            if (protocolLevelException != null) {
                span = newSpan("close connection due to protocol error");
                TracingHelper.logError(span, protocolLevelException);
                reason = "protocol error: " + protocolLevelException.toString();
            } else if (stopCalled()) {
                span = newSpan("close device connection (server shutdown)");
                reason = "server shutdown";
            } else {
                span = newSpan("CLOSE");
                reason = null;
            }
            onCloseInternal(span, reason, true)
                    .onComplete(ar -> span.finish());
        }

        private Future<Void> onCloseInternal(final Span span, final String reason, final boolean sendDisconnectedEvent) {
            AbstractVertxBasedMqttProtocolAdapter.this.onBeforeEndpointClose(this);
            AbstractVertxBasedMqttProtocolAdapter.this.onClose(endpoint);
            final CompositeFuture removalDoneFuture = removeAllCommandSubscriptions(span, sendDisconnectedEvent);
            if (sendDisconnectedEvent) {
                sendDisconnectedEvent(endpoint.clientIdentifier(), authenticatedDevice, span.context());
            }
            if (authenticatedDevice == null) {
                metrics.decrementUnauthenticatedConnections();
            } else {
                metrics.decrementConnections(authenticatedDevice.getTenantId());
            }
            final String reasonSuffix = reason != null ? ("; reason: " + reason) : "";
            if (endpoint.isConnected()) {
                final Map<String, String> logFields = new HashMap<>(2);
                logFields.put(Fields.EVENT, "closing device connection");
                Optional.ofNullable(reason).ifPresent(r -> logFields.put("reason", r));
                span.log(logFields);

                if (authenticatedDevice == null) {
                    log.debug("closing connection to anonymous device [client ID: {}]{}", endpoint.clientIdentifier(), reasonSuffix);
                } else {
                    log.debug("closing connection to device [tenant-id: {}, device-id: {}, client ID: {}]{}",
                            authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId(),
                            endpoint.clientIdentifier(), reasonSuffix);
                }
                endpoint.close();
            } else {
                // connection already closed
                if (authenticatedDevice == null) {
                    log.debug("connection to anonymous device closed [client ID: {}]{}", endpoint.clientIdentifier(), reasonSuffix);
                } else {
                    log.debug("connection to device closed [tenant-id: {}, device-id: {}, client ID: {}]{}",
                            authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId(),
                            endpoint.clientIdentifier(), reasonSuffix);
                }
            }
            return removalDoneFuture.mapEmpty();
        }

        private CompositeFuture removeAllCommandSubscriptions(final Span span, final boolean sendDisconnectedEvent) {
            @SuppressWarnings("rawtypes")
            final List<Future> removalFutures = new ArrayList<>(commandSubscriptions.size());
            for (final var iter = commandSubscriptions.values().iterator(); iter.hasNext();) {
                final Pair<CommandSubscription, CommandConsumer> pair = iter.next();
                pair.one().logUnsubscribe(span);
                removalFutures.add(onCommandSubscriptionRemoved(pair, span, sendDisconnectedEvent));
                iter.remove();
            }
            return CompositeFuture.join(removalFutures);
        }

        private Span newSpan(final String operationName) {
            final Span span = newChildSpan(null, operationName);
            traceSamplingPriority.ifPresent(prio -> TracingHelper.setTraceSamplingPriority(span, prio));
            return span;
        }

        private Span newChildSpan(final SpanContext spanContext, final String operationName) {
            final Span span = TracingHelper.buildChildSpan(tracer, spanContext, operationName, getTypeName())
                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                    .withTag(TracingHelper.TAG_CLIENT_ID.getKey(), endpoint.clientIdentifier())
                    .withTag(TracingHelper.TAG_AUTHENTICATED.getKey(), authenticatedDevice != null)
                    .start();

            if (authenticatedDevice != null) {
                TracingHelper.setDeviceTags(span, authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());
            }
            return span;
        }
    }

    /**
     * Get the buffer to send as a command to the gateway/device.
     * <p>
     * Subclasses should override this method in order to overwrite the provided command.
     *
     * @param ctx The command context for this command.
     * @return A future containing the mapped buffer.
     */
    protected Future<Buffer> getCommandPayload(final CommandContext ctx) {
        return Future.succeededFuture(ctx.getCommand().getPayload());
    }
}
