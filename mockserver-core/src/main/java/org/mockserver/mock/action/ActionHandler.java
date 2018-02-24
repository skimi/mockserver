package org.mockserver.mock.action;

import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.client.netty.NettyHttpClient;
import org.mockserver.client.netty.SocketCommunicationException;
import org.mockserver.client.netty.SocketConnectionException;
import org.mockserver.client.netty.proxy.ProxyConfiguration;
import org.mockserver.client.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.model.ExpectationMatchLogEntry;
import org.mockserver.log.model.RequestLogEntry;
import org.mockserver.log.model.RequestResponseLogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpStateHandler;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAPI;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.cors.CORSHeaders.isPreflightRequest;
import static org.mockserver.log.model.MessageLogEntry.LogMessageType.*;
import static org.mockserver.model.HttpResponse.notFoundResponse;

/**
 * @author jamesdbloom
 */
public class ActionHandler {

    public static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");

    private final HttpStateHandler httpStateHandler;
    private final Scheduler scheduler;
    private MockServerLogger mockServerLogger;
    private HttpResponseActionHandler httpResponseActionHandler;
    private HttpResponseTemplateActionHandler httpResponseTemplateActionHandler;
    private HttpResponseClassCallbackActionHandler httpResponseClassCallbackActionHandler;
    private HttpResponseObjectCallbackActionHandler httpResponseObjectCallbackActionHandler;
    private HttpForwardActionHandler httpForwardActionHandler;
    private HttpForwardTemplateActionHandler httpForwardTemplateActionHandler;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;
    private HttpForwardObjectCallbackActionHandler httpForwardObjectCallbackActionHandler;
    private HttpOverrideForwardedRequestActionHandler httpOverrideForwardedRequestCallbackActionHandler;
    private HttpErrorActionHandler httpErrorActionHandler;

    // forwarding
    private NettyHttpClient httpClient;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer();

    public ActionHandler(HttpStateHandler httpStateHandler, ProxyConfiguration proxyConfiguration) {
        this.httpStateHandler = httpStateHandler;
        this.scheduler = httpStateHandler.getScheduler();
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.httpClient = new NettyHttpClient(proxyConfiguration);
        this.httpResponseActionHandler = new HttpResponseActionHandler();
        this.httpResponseTemplateActionHandler = new HttpResponseTemplateActionHandler(mockServerLogger);
        this.httpResponseClassCallbackActionHandler = new HttpResponseClassCallbackActionHandler(mockServerLogger);
        this.httpResponseObjectCallbackActionHandler = new HttpResponseObjectCallbackActionHandler(httpStateHandler);
        this.httpForwardActionHandler = new HttpForwardActionHandler(mockServerLogger, httpClient);
        this.httpForwardTemplateActionHandler = new HttpForwardTemplateActionHandler(mockServerLogger, httpClient);
        this.httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(mockServerLogger, httpClient);
        this.httpForwardObjectCallbackActionHandler = new HttpForwardObjectCallbackActionHandler(httpStateHandler, httpClient);
        this.httpOverrideForwardedRequestCallbackActionHandler = new HttpOverrideForwardedRequestActionHandler(mockServerLogger, httpClient);
        this.httpErrorActionHandler = new HttpErrorActionHandler();
    }

    public void processAction(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyThisRequest, final boolean synchronous) {
        final Expectation expectation = httpStateHandler.firstMatchingExpectation(request);
        if (request.getHeaders().containsEntry("x-forwarded-by", "MockServer")) {
            mockServerLogger.trace("Received \"x-forwarded-by\" header caused by exploratory HTTP proxy - falling back to no proxy: {}", request);
            returnNotFound(responseWriter, request);
        } else if (expectation != null && expectation.getAction() != null) {
            Action action = expectation.getAction();
            switch (action.getType()) {
                case RESPONSE: {
                    final HttpResponse httpResponse = (HttpResponse) action;
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseActionHandler.handle(httpResponse);
                            responseWriter.writeResponse(request, response, false);
                            mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                        }
                    }, httpResponse.getDelay(), synchronous);
                    break;
                }
                case RESPONSE_TEMPLATE: {
                    final HttpTemplate httpTemplate = (HttpTemplate) action;
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseTemplateActionHandler.handle(httpTemplate, request);
                            responseWriter.writeResponse(request, response, false);
                            mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                        }
                    }, httpTemplate.getDelay(), synchronous);
                    break;
                }
                case RESPONSE_CLASS_CALLBACK: {
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    final HttpClassCallback classCallback = (HttpClassCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseClassCallbackActionHandler.handle(classCallback, request);
                            responseWriter.writeResponse(request, response, false);
                            mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                        }
                    }, synchronous);
                    break;
                }
                case RESPONSE_OBJECT_CALLBACK: {
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    final HttpObjectCallback objectCallback = (HttpObjectCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            httpResponseObjectCallbackActionHandler.handle(objectCallback, request, responseWriter);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD: {
                    final HttpForward httpForward = (HttpForward) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final SettableFuture<HttpResponse> responseFuture = httpForwardActionHandler.handle(httpForward, request);
                            scheduler.submit(responseFuture, new Runnable() {
                                public void run() {
                                    try {
                                        HttpResponse response = responseFuture.get();
                                        responseWriter.writeResponse(request, response, false);
                                        httpStateHandler.log(new RequestResponseLogEntry(request, response));
                                        mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                                    } catch (Exception ex) {
                                        mockServerLogger.error(request, ex, ex.getMessage());
                                    }
                                }
                            }, synchronous);
                        }
                    }, httpForward.getDelay(), synchronous);
                    break;
                }
                case FORWARD_TEMPLATE: {
                    final HttpTemplate httpTemplate = (HttpTemplate) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final SettableFuture<HttpResponse> responseFuture = httpForwardTemplateActionHandler.handle(httpTemplate, request);
                            scheduler.submit(responseFuture, new Runnable() {
                                public void run() {
                                    try {
                                        HttpResponse response = responseFuture.get();
                                        responseWriter.writeResponse(request, response, false);
                                        httpStateHandler.log(new RequestResponseLogEntry(request, response));
                                        mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                                    } catch (Exception ex) {
                                        mockServerLogger.error(request, ex, ex.getMessage());
                                    }
                                }
                            }, synchronous);
                        }
                    }, httpTemplate.getDelay(), synchronous);
                    break;
                }
                case FORWARD_CLASS_CALLBACK: {
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    final HttpClassCallback classCallback = (HttpClassCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            final SettableFuture<HttpResponse> responseFuture = httpForwardClassCallbackActionHandler.handle(classCallback, request);
                            scheduler.submit(responseFuture, new Runnable() {
                                public void run() {
                                    try {
                                        HttpResponse response = responseFuture.get();
                                        responseWriter.writeResponse(request, response, false);
                                        mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                                    } catch (Exception ex) {
                                        mockServerLogger.error(request, ex, ex.getMessage());
                                    }
                                }
                            }, synchronous);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD_OBJECT_CALLBACK: {
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    final HttpObjectCallback objectCallback = (HttpObjectCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            httpForwardObjectCallbackActionHandler.handle(objectCallback, request, responseWriter, synchronous);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD_REPLACE: {
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    final HttpOverrideForwardedRequest httpOverrideForwardedRequest = (HttpOverrideForwardedRequest) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final SettableFuture<HttpResponse> responseFuture = httpOverrideForwardedRequestCallbackActionHandler.handle(httpOverrideForwardedRequest, request);
                            scheduler.submit(responseFuture, new Runnable() {
                                public void run() {
                                    try {
                                        HttpResponse response = responseFuture.get();
                                        responseWriter.writeResponse(request, response, false);
                                        mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", response, request, expectation.clone());
                                    } catch (Exception ex) {
                                        mockServerLogger.error(request, ex, ex.getMessage());
                                    }
                                }
                            }, synchronous);
                        }
                    }, httpOverrideForwardedRequest.getDelay(), synchronous);
                    break;
                }
                case ERROR: {
                    final HttpError httpError = (HttpError) action;
                    httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            httpErrorActionHandler.handle(httpError, ctx);
                            mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning error:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " for expectation:{}", httpError, request, expectation.clone());
                        }
                    }, httpError.getDelay(), synchronous);
                    break;
                }
            }
        } else if ((enableCORSForAPI() || enableCORSForAllResponses()) && isPreflightRequest(request)) {

            responseWriter.writeResponse(request, OK);

        } else if (proxyThisRequest || (!StringUtils.isEmpty(request.getFirstHeader(HOST.toString())) && !localAddresses.contains(request.getFirstHeader(HOST.toString())))) {

            final boolean exploratoryHttpProxy = !proxyThisRequest;
            final InetSocketAddress remoteAddress = ctx != null ? ctx.channel().attr(REMOTE_SOCKET).get() : null;
            final HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request);
            if (exploratoryHttpProxy) {
                clonedRequest.withHeader("x-forwarded-by", "MockServer");
            }
            final SettableFuture<HttpResponse> responseFuture = httpClient.sendRequest(clonedRequest, remoteAddress, exploratoryHttpProxy ? 1000 : ConfigurationProperties.socketConnectionTimeout());
            scheduler.submit(responseFuture, new Runnable() {
                public void run() {
                    try {
                        HttpResponse response = responseFuture.get();
                        if (response == null) {
                            response = notFoundResponse();
                        }
                        responseWriter.writeResponse(request, response, false);
                        if (response.containsHeader("x-forwarded-by", "MockServer")) {
                            httpStateHandler.log(new RequestLogEntry(request));
                            mockServerLogger.info(EXPECTATION_NOT_MATCHED, request, "no matching expectation - returning:{}" + NEW_LINE + " for request:{}", notFoundResponse(), request);
                        } else {
                            httpStateHandler.log(new RequestResponseLogEntry(request, response));
                            mockServerLogger.info(FORWARDED_REQUEST,
                                request,
                                "returning response:{}" + NEW_LINE + " for request:{}" + NEW_LINE + " as curl:{}",
                                response,
                                request,
                                httpRequestToCurlSerializer.toCurl(request, remoteAddress)
                            );
                        }
                    } catch (SocketCommunicationException sce) {
                        returnNotFound(responseWriter, request);
                    } catch (Exception ex) {
                        if (exploratoryHttpProxy && (ex.getCause() instanceof ConnectException || ex.getCause() instanceof SocketConnectionException)) {
                            mockServerLogger.trace("Failed to connect to proxied socket due to exploratory HTTP proxy for: {}" + NEW_LINE + " falling back to no proxy: {}", request, ex.getCause());
                            returnNotFound(responseWriter, request);
                        } else {
                            mockServerLogger.error(request, ex, ex.getMessage());
                        }
                    }
                }
            }, synchronous);

        } else {
            returnNotFound(responseWriter, request);
        }
    }

    private void returnNotFound(ResponseWriter responseWriter, HttpRequest request) {
        HttpResponse response = notFoundResponse();
        if (request.getHeaders().containsEntry("x-forwarded-by", "MockServer")) {
            response.withHeader("x-forwarded-by", "MockServer");
        } else {
            httpStateHandler.log(new RequestLogEntry(request));
            mockServerLogger.info(EXPECTATION_NOT_MATCHED, request, "no matching expectation - returning:{}" + NEW_LINE + " for request:{}", notFoundResponse(), request);
        }
        responseWriter.writeResponse(request, response, false);
    }

}
