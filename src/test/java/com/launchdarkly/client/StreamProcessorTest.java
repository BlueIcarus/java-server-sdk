package com.launchdarkly.client;

import com.launchdarkly.eventsource.ConnectionErrorHandler;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

import static com.launchdarkly.client.TestHttpUtil.eventStreamResponse;
import static com.launchdarkly.client.TestHttpUtil.makeStartedServer;
import static com.launchdarkly.client.TestUtil.specificFeatureStore;
import static com.launchdarkly.client.VersionedDataKind.FEATURES;
import static com.launchdarkly.client.VersionedDataKind.SEGMENTS;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockWebServer;

@SuppressWarnings("javadoc")
public class StreamProcessorTest extends EasyMockSupport {

  private static final String SDK_KEY = "sdk_key";
  private static final URI STREAM_URI = URI.create("http://stream.test.com");
  private static final String FEATURE1_KEY = "feature1";
  private static final int FEATURE1_VERSION = 11;
  private static final FeatureFlag FEATURE = new FeatureFlagBuilder(FEATURE1_KEY).version(FEATURE1_VERSION).build();
  private static final String SEGMENT1_KEY = "segment1";
  private static final int SEGMENT1_VERSION = 22;
  private static final Segment SEGMENT = new Segment.Builder(SEGMENT1_KEY).version(SEGMENT1_VERSION).build();
  private static final String STREAM_RESPONSE_WITH_EMPTY_DATA =
      "event: put\n" +
      "data: {\"data\":{\"flags\":{},\"segments\":{}}}\n\n";

  private InMemoryFeatureStore featureStore;
  private LDConfig.Builder configBuilder;
  private FeatureRequestor mockRequestor;
  private EventSource mockEventSource;
  private EventHandler eventHandler;
  private URI actualStreamUri;
  private ConnectionErrorHandler errorHandler;
  private Headers headers;

  @Before
  public void setup() {
    featureStore = new InMemoryFeatureStore();
    configBuilder = new LDConfig.Builder().featureStoreFactory(specificFeatureStore(featureStore));
    mockRequestor = createStrictMock(FeatureRequestor.class);
    mockEventSource = createStrictMock(EventSource.class);
  }
  
  @Test
  public void streamUriHasCorrectEndpoint() {
    LDConfig config = configBuilder.streamURI(STREAM_URI).build();
    createStreamProcessor(SDK_KEY, config).start();
    assertEquals(URI.create(STREAM_URI.toString() + "/all"), actualStreamUri);
  }
  
  @Test
  public void headersHaveAuthorization() {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    assertEquals(SDK_KEY, headers.get("Authorization"));
  }
  
  @Test
  public void headersHaveUserAgent() {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    assertEquals("JavaClient/" + LDClient.CLIENT_VERSION, headers.get("User-Agent"));
  }

  @Test
  public void headersHaveAccept() {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    assertEquals("text/event-stream", headers.get("Accept"));
  }

  @Test
  public void putCausesFeatureToBeStored() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    MessageEvent event = new MessageEvent("{\"data\":{\"flags\":{\"" +
        FEATURE1_KEY + "\":" + featureJson(FEATURE1_KEY, FEATURE1_VERSION) + "}," +
        "\"segments\":{}}}");
    eventHandler.onMessage("put", event);
    
    assertFeatureInStore(FEATURE);
  }

  @Test
  public void putCausesSegmentToBeStored() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    MessageEvent event = new MessageEvent("{\"data\":{\"flags\":{},\"segments\":{\"" +
        SEGMENT1_KEY + "\":" + segmentJson(SEGMENT1_KEY, SEGMENT1_VERSION) + "}}}");
    eventHandler.onMessage("put", event);
    
    assertSegmentInStore(SEGMENT);
  }
  
  @Test
  public void storeNotInitializedByDefault() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    assertFalse(featureStore.initialized());
  }
  
  @Test
  public void putCausesStoreToBeInitialized() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("put", emptyPutEvent());
    assertTrue(featureStore.initialized());
  }

  @Test
  public void processorNotInitializedByDefault() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    sp.start();
    assertFalse(sp.initialized());
  }
  
  @Test
  public void putCausesProcessorToBeInitialized() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    sp.start();
    eventHandler.onMessage("put", emptyPutEvent());
    assertTrue(sp.initialized());
  }

  @Test
  public void futureIsNotSetByDefault() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    Future<Void> future = sp.start();
    assertFalse(future.isDone());
  }

  @Test
  public void putCausesFutureToBeSet() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    Future<Void> future = sp.start();
    eventHandler.onMessage("put", emptyPutEvent());
    assertTrue(future.isDone());
  }

  @Test
  public void patchUpdatesFeature() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("put", emptyPutEvent());
    
    String path = "/flags/" + FEATURE1_KEY;
    MessageEvent event = new MessageEvent("{\"path\":\"" + path + "\",\"data\":" +
        featureJson(FEATURE1_KEY, FEATURE1_VERSION) + "}");
    eventHandler.onMessage("patch", event);
    
    assertFeatureInStore(FEATURE);
  }

  @Test
  public void patchUpdatesSegment() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("put", emptyPutEvent());
    
    String path = "/segments/" + SEGMENT1_KEY;
    MessageEvent event = new MessageEvent("{\"path\":\"" + path + "\",\"data\":" +
        segmentJson(SEGMENT1_KEY, SEGMENT1_VERSION) + "}");
    eventHandler.onMessage("patch", event);
    
    assertSegmentInStore(SEGMENT);
  }

  @Test
  public void deleteDeletesFeature() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("put", emptyPutEvent());
    featureStore.upsert(FEATURES, FEATURE);
    
    String path = "/flags/" + FEATURE1_KEY;
    MessageEvent event = new MessageEvent("{\"path\":\"" + path + "\",\"version\":" +
        (FEATURE1_VERSION + 1) + "}");
    eventHandler.onMessage("delete", event);
    
    assertNull(featureStore.get(FEATURES, FEATURE1_KEY));
  }
  
  @Test
  public void deleteDeletesSegment() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("put", emptyPutEvent());
    featureStore.upsert(SEGMENTS, SEGMENT);
    
    String path = "/segments/" + SEGMENT1_KEY;
    MessageEvent event = new MessageEvent("{\"path\":\"" + path + "\",\"version\":" +
        (SEGMENT1_VERSION + 1) + "}");
    eventHandler.onMessage("delete", event);
    
    assertNull(featureStore.get(SEGMENTS, SEGMENT1_KEY));
  }
  
  @Test
  public void indirectPutRequestsAndStoresFeature() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    setupRequestorToReturnAllDataWithFlag(FEATURE);
    replayAll();
    
    eventHandler.onMessage("indirect/put", new MessageEvent(""));
    
    assertFeatureInStore(FEATURE);
  }

  @Test
  public void indirectPutInitializesStore() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    setupRequestorToReturnAllDataWithFlag(FEATURE);
    replayAll();
    
    eventHandler.onMessage("indirect/put", new MessageEvent(""));
    
    assertTrue(featureStore.initialized());
  }

  @Test
  public void indirectPutInitializesProcessor() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    sp.start();
    setupRequestorToReturnAllDataWithFlag(FEATURE);
    replayAll();
    
    eventHandler.onMessage("indirect/put", new MessageEvent(""));
    
    assertTrue(featureStore.initialized());
  }

  @Test
  public void indirectPutSetsFuture() throws Exception {
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    Future<Void> future = sp.start();
    setupRequestorToReturnAllDataWithFlag(FEATURE);
    replayAll();
    
    eventHandler.onMessage("indirect/put", new MessageEvent(""));
    
    assertTrue(future.isDone());
  }
  
  @Test
  public void indirectPatchRequestsAndUpdatesFeature() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    expect(mockRequestor.getFlag(FEATURE1_KEY)).andReturn(FEATURE);
    replayAll();
    
    eventHandler.onMessage("put", emptyPutEvent());
    eventHandler.onMessage("indirect/patch", new MessageEvent("/flags/" + FEATURE1_KEY));
    
    assertFeatureInStore(FEATURE);
  }

  @Test
  public void indirectPatchRequestsAndUpdatesSegment() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    expect(mockRequestor.getSegment(SEGMENT1_KEY)).andReturn(SEGMENT);
    replayAll();
    
    eventHandler.onMessage("put", emptyPutEvent());
    eventHandler.onMessage("indirect/patch", new MessageEvent("/segments/" + SEGMENT1_KEY));
    
    assertSegmentInStore(SEGMENT);
  }
  
  @Test
  public void unknownEventTypeDoesNotThrowException() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    eventHandler.onMessage("what", new MessageEvent(""));
  }
  
  @Test
  public void streamWillReconnectAfterGeneralIOException() throws Exception {
    createStreamProcessor(SDK_KEY, configBuilder.build()).start();
    ConnectionErrorHandler.Action action = errorHandler.onConnectionError(new IOException());
    assertEquals(ConnectionErrorHandler.Action.PROCEED, action);
  }

  @Test
  public void http400ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(400);
  }
  
  @Test
  public void http401ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(401);
  }

  @Test
  public void http403ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(403);
  }

  @Test
  public void http408ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(408);
  }

  @Test
  public void http429ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(429);
  }

  @Test
  public void http500ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(500);
  }

  // There are already end-to-end tests against an HTTP server in okhttp-eventsource, so we won't retest the
  // basic stream mechanism in detail. However, we do want to make sure that the LDConfig options are correctly
  // applied to the EventSource for things like TLS configuration.
  
  @Test
  public void httpClientDoesNotAllowSelfSignedCertByDefault() throws Exception {
    final ConnectionErrorSink errorSink = new ConnectionErrorSink();
    try (TestHttpUtil.ServerWithCert server = new TestHttpUtil.ServerWithCert()) {
      server.server.enqueue(eventStreamResponse(STREAM_RESPONSE_WITH_EMPTY_DATA));

      LDConfig config = new LDConfig.Builder()
          .streamURI(server.uri())
          .build();
      
      try (StreamProcessor sp = new StreamProcessor("sdk-key", config,
          mockRequestor, featureStore, null)) {
        sp.connectionErrorHandler = errorSink;
        Future<Void> ready = sp.start();
        ready.get();
        
        Throwable error = errorSink.errors.peek();
        assertNotNull(error);
        assertEquals(SSLHandshakeException.class, error.getClass());
      }
    }
  }
  
  @Test
  public void httpClientCanUseCustomTlsConfig() throws Exception {
    final ConnectionErrorSink errorSink = new ConnectionErrorSink();
    try (TestHttpUtil.ServerWithCert server = new TestHttpUtil.ServerWithCert()) {
      server.server.enqueue(eventStreamResponse(STREAM_RESPONSE_WITH_EMPTY_DATA));
      
      LDConfig config = new LDConfig.Builder()
          .streamURI(server.uri())
          .sslSocketFactory(server.sslClient.socketFactory, server.sslClient.trustManager) // allows us to trust the self-signed cert
          .build();
      
      try (StreamProcessor sp = new StreamProcessor("sdk-key", config,
          mockRequestor, featureStore, null)) {
        sp.connectionErrorHandler = errorSink;
        Future<Void> ready = sp.start();
        ready.get();
        
        assertNull(errorSink.errors.peek());
      }
    }
  }

  @Test
  public void httpClientCanUseProxyConfig() throws Exception {
    final ConnectionErrorSink errorSink = new ConnectionErrorSink();
    URI fakeStreamUri = URI.create("http://not-a-real-host");
    try (MockWebServer server = makeStartedServer(eventStreamResponse(STREAM_RESPONSE_WITH_EMPTY_DATA))) {
      HttpUrl serverUrl = server.url("/");
      LDConfig config = new LDConfig.Builder()
          .streamURI(fakeStreamUri)
          .proxyHost(serverUrl.host())
          .proxyPort(serverUrl.port())
          .build();
      
      try (StreamProcessor sp = new StreamProcessor("sdk-key", config,
          mockRequestor, featureStore, null)) {
        sp.connectionErrorHandler = errorSink;
        Future<Void> ready = sp.start();
        ready.get();
        
        assertNull(errorSink.errors.peek());
        assertEquals(1, server.getRequestCount());
      }
    }
  }
  
  static class ConnectionErrorSink implements ConnectionErrorHandler {
    final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    
    public Action onConnectionError(Throwable t) {
      errors.add(t);
      return Action.SHUTDOWN;
    }
  }
  
  private void testUnrecoverableHttpError(int status) throws Exception {
    UnsuccessfulResponseException e = new UnsuccessfulResponseException(status);
    long startTime = System.currentTimeMillis();
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    Future<Void> initFuture = sp.start();
    
    ConnectionErrorHandler.Action action = errorHandler.onConnectionError(e);
    assertEquals(ConnectionErrorHandler.Action.SHUTDOWN, action);
    
    try {
      initFuture.get(10, TimeUnit.SECONDS);
    } catch (TimeoutException ignored) {
      fail("Should not have timed out");
    }
    assertTrue((System.currentTimeMillis() - startTime) < 9000);
    assertTrue(initFuture.isDone());
    assertFalse(sp.initialized());
  }
  
  private void testRecoverableHttpError(int status) throws Exception {
    UnsuccessfulResponseException e = new UnsuccessfulResponseException(status);
    long startTime = System.currentTimeMillis();
    StreamProcessor sp = createStreamProcessor(SDK_KEY, configBuilder.build());
    Future<Void> initFuture = sp.start();
    
    ConnectionErrorHandler.Action action = errorHandler.onConnectionError(e);
    assertEquals(ConnectionErrorHandler.Action.PROCEED, action);
    
    try {
      initFuture.get(200, TimeUnit.MILLISECONDS);
      fail("Expected timeout");
    } catch (TimeoutException ignored) {
    }
    assertTrue((System.currentTimeMillis() - startTime) >= 200);
    assertFalse(initFuture.isDone());
    assertFalse(sp.initialized());
  }
  
  private StreamProcessor createStreamProcessor(String sdkKey, LDConfig config) {
    return new StreamProcessor(sdkKey, config, mockRequestor, featureStore, new StubEventSourceCreator());
  }
  
  private String featureJson(String key, int version) {
    return "{\"key\":\"" + key + "\",\"version\":" + version + ",\"on\":true}";
  }
  
  private String segmentJson(String key, int version) {
    return "{\"key\":\"" + key + "\",\"version\":" + version + ",\"includes\":[],\"excludes\":[],\"rules\":[]}";
  }
  
  private MessageEvent emptyPutEvent() {
    return new MessageEvent("{\"data\":{\"flags\":{},\"segments\":{}}}");
  }
  
  private void setupRequestorToReturnAllDataWithFlag(FeatureFlag feature) throws Exception {
    FeatureRequestor.AllData data = new FeatureRequestor.AllData(
        Collections.singletonMap(feature.getKey(), feature), Collections.<String, Segment>emptyMap());
    expect(mockRequestor.getAllData()).andReturn(data);
  }
  
  private void assertFeatureInStore(FeatureFlag feature) {
    assertEquals(feature.getVersion(), featureStore.get(FEATURES, feature.getKey()).getVersion());
  }
  
  private void assertSegmentInStore(Segment segment) {
    assertEquals(segment.getVersion(), featureStore.get(SEGMENTS, segment.getKey()).getVersion());
  }
  
  private class StubEventSourceCreator implements StreamProcessor.EventSourceCreator {
    public EventSource createEventSource(LDConfig config, EventHandler handler, URI streamUri, ConnectionErrorHandler errorHandler,
        Headers headers) {  
      StreamProcessorTest.this.eventHandler = handler;
      StreamProcessorTest.this.actualStreamUri = streamUri;
      StreamProcessorTest.this.errorHandler = errorHandler;
      StreamProcessorTest.this.headers = headers;
      return mockEventSource;
    }
  }
}
