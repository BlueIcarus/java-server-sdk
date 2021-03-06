package com.launchdarkly.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.launchdarkly.client.value.LDValue;

import org.junit.Test;

import static com.launchdarkly.client.TestHttpUtil.baseConfig;
import static com.launchdarkly.client.TestHttpUtil.httpsServerWithSelfSignedCert;
import static com.launchdarkly.client.TestHttpUtil.jsonResponse;
import static com.launchdarkly.client.TestHttpUtil.makeStartedServer;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@SuppressWarnings("javadoc")
public class LDClientEndToEndTest {
  private static final Gson gson = new Gson();
  private static final String sdkKey = "sdk-key";
  private static final String flagKey = "flag1";
  private static final FeatureFlag flag = new FeatureFlagBuilder(flagKey)
      .offVariation(0).variations(LDValue.of(true))
      .build();
  private static final LDUser user = new LDUser("user-key");
  
  @Test
  public void clientStartsInPollingMode() throws Exception {
    MockResponse resp = jsonResponse(makeAllDataJson());
    
    try (MockWebServer server = makeStartedServer(resp)) {
      LDConfig config = baseConfig(server)
          .stream(false)
          .sendEvents(false)
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertTrue(client.initialized());
        assertTrue(client.boolVariation(flagKey, user, false));
      }
    }
  }

  @Test
  public void clientFailsInPollingModeWith401Error() throws Exception {
    MockResponse resp = new MockResponse().setResponseCode(401);
    
    try (MockWebServer server = makeStartedServer(resp)) {
      LDConfig config = baseConfig(server)
          .stream(false)
          .sendEvents(false)
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertFalse(client.initialized());
        assertFalse(client.boolVariation(flagKey, user, false));
      }
    }
  }

  @Test
  public void clientStartsInPollingModeWithSelfSignedCert() throws Exception {
    MockResponse resp = jsonResponse(makeAllDataJson());
    
    try (TestHttpUtil.ServerWithCert serverWithCert = httpsServerWithSelfSignedCert(resp)) {
      LDConfig config = baseConfig(serverWithCert.server)
          .stream(false)
          .sendEvents(false)
          .sslSocketFactory(serverWithCert.sslClient.socketFactory, serverWithCert.sslClient.trustManager) // allows us to trust the self-signed cert
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertTrue(client.initialized());
        assertTrue(client.boolVariation(flagKey, user, false));
      }
    }
  }

  @Test
  public void clientStartsInStreamingMode() throws Exception {
    String streamData = "event: put\n" +
        "data: {\"data\":" + makeAllDataJson() + "}\n\n";    
    MockResponse resp = TestHttpUtil.eventStreamResponse(streamData);
    
    try (MockWebServer server = makeStartedServer(resp)) {
      LDConfig config = baseConfig(server)
          .sendEvents(false)
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertTrue(client.initialized());
        assertTrue(client.boolVariation(flagKey, user, false));
      }
    }
  }

  @Test
  public void clientFailsInStreamingModeWith401Error() throws Exception {
    MockResponse resp = new MockResponse().setResponseCode(401);
    
    try (MockWebServer server = makeStartedServer(resp)) {
      LDConfig config = baseConfig(server)
          .sendEvents(false)
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertFalse(client.initialized());
        assertFalse(client.boolVariation(flagKey, user, false));
      }
    }
  }

  @Test
  public void clientStartsInStreamingModeWithSelfSignedCert() throws Exception {
    String streamData = "event: put\n" +
        "data: {\"data\":" + makeAllDataJson() + "}\n\n";    
    MockResponse resp = TestHttpUtil.eventStreamResponse(streamData);
    
    try (TestHttpUtil.ServerWithCert serverWithCert = httpsServerWithSelfSignedCert(resp)) {
      LDConfig config = baseConfig(serverWithCert.server)
          .sendEvents(false)
          .sslSocketFactory(serverWithCert.sslClient.socketFactory, serverWithCert.sslClient.trustManager) // allows us to trust the self-signed cert
          .build();
      
      try (LDClient client = new LDClient(sdkKey, config)) {
        assertTrue(client.initialized());
        assertTrue(client.boolVariation(flagKey, user, false));
      }
    }
  }

  public String makeAllDataJson() {
    JsonObject flagsData = new JsonObject();
    flagsData.add(flagKey, gson.toJsonTree(flag));
    JsonObject allData = new JsonObject();
    allData.add("flags", flagsData);
    allData.add("segments", new JsonObject());
    return gson.toJson(allData);
  }
}
