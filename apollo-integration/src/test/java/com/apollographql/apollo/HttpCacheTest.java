package com.apollographql.apollo;

import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.api.cache.http.HttpCache;
import com.apollographql.apollo.api.cache.http.HttpCachePolicy;
import com.apollographql.apollo.cache.http.ApolloHttpCache;
import com.apollographql.apollo.cache.http.DiskLruHttpCacheStore;
import com.apollographql.apollo.exception.ApolloException;
import com.apollographql.apollo.exception.ApolloHttpException;
import com.apollographql.apollo.integration.httpcache.AllFilmsQuery;
import com.apollographql.apollo.integration.httpcache.AllPlanetsQuery;
import com.apollographql.apollo.integration.httpcache.DroidDetailsQuery;
import com.apollographql.apollo.integration.httpcache.type.CustomType;
import com.apollographql.apollo.internal.interceptor.ApolloServerInterceptor;
import com.apollographql.apollo.rx2.Rx2Apollo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.reactivex.functions.Predicate;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.internal.io.FileSystem;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import static com.google.common.truth.Truth.assertThat;

@SuppressWarnings("SimpleDateFormatConstant")
public class HttpCacheTest {
  private static final int TIME_OUT_SECONDS = 3;
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

  private ApolloClient apolloClient;
  private okhttp3.Request lastHttRequest;
  private okhttp3.Response lastHttResponse;
  private MockHttpCacheStore cacheStore;
  private OkHttpClient okHttpClient;
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final InMemoryFileSystem inMemoryFileSystem = new InMemoryFileSystem();

  @Before public void setUp() {
    CustomTypeAdapter<Date> dateCustomTypeAdapter = new CustomTypeAdapter<Date>() {
      @Override public Date decode(String value) {
        try {
          return DATE_FORMAT.parse(value);
        } catch (ParseException e) {
          throw new RuntimeException(e);
        }
      }

      @Override public String encode(Date value) {
        return DATE_FORMAT.format(value);
      }
    };

    cacheStore = new MockHttpCacheStore();
    cacheStore.delegate = new DiskLruHttpCacheStore(inMemoryFileSystem, new File("/cache/"), Integer.MAX_VALUE);

    HttpCache cache = new ApolloHttpCache(cacheStore, null);
    okHttpClient = new OkHttpClient.Builder()
        .addInterceptor(new TrackingInterceptor())
        .addInterceptor(cache.interceptor())
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build();

    apolloClient = ApolloClient.builder()
        .serverUrl(server.url("/"))
        .okHttpClient(okHttpClient)
        .addCustomTypeAdapter(CustomType.DATE, dateCustomTypeAdapter)
        .httpCache(cache)
        .build();
  }

  @After public void tearDown() {
    try {
      apolloClient.clearHttpCache();
      server.shutdown();
    } catch (Exception ignore) {
    }
  }

  @Test public void prematureDisconnect() throws Exception {
    MockResponse mockResponse = mockResponse("/HttpCacheTestAllPlanets.json");
    Buffer truncatedBody = new Buffer();
    truncatedBody.write(mockResponse.getBody(), 16);
    mockResponse.setBody(truncatedBody);
    server.enqueue(mockResponse);

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertError(ApolloException.class);

    checkNoCachedResponse();
  }

  @Test public void cacheDefault() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheSeveralResponses() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    enqueueResponse("/HttpCacheTestDroidDetails.json");
    Rx2Apollo.from(apolloClient
        .query(new DroidDetailsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<DroidDetailsQuery.Data>>() {
          @Override public boolean test(Response<DroidDetailsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestDroidDetails.json");

    enqueueResponse("/HttpCacheTestAllFilms.json");
    Rx2Apollo.from(apolloClient
        .query(new AllFilmsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllFilmsQuery.Data>>() {
          @Override public boolean test(Response<AllFilmsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllFilms.json");
  }

  @Test public void noCacheStore() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");

    ApolloClient apolloClient = ApolloClient.builder()
        .serverUrl(server.url("/"))
        .okHttpClient(new OkHttpClient.Builder()
            .addInterceptor(new TrackingInterceptor())
            .build())
        .build();

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkNoCachedResponse();
  }

  @Test public void networkOnly() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()).httpCachePolicy(HttpCachePolicy.NETWORK_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void networkOnly_responseWithGraphError_noCached() throws Exception {
    enqueueResponse("/ResponseError.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()).httpCachePolicy(HttpCachePolicy.NETWORK_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return response.hasErrors();
          }
        });
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkNoCachedResponse();
  }

  @Test public void cacheOnlyHit() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    assertThat(server.takeRequest()).isNotNull();

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(lastHttResponse.networkResponse()).isNull();
    assertThat(lastHttResponse.cacheResponse()).isNotNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheOnlyMiss() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertError(ApolloHttpException.class);
  }

  @Test public void cacheNonStale() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.takeRequest()).isNotNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST.expireAfter(TIME_OUT_SECONDS, TimeUnit.SECONDS)))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);

    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(lastHttResponse.networkResponse()).isNull();
    assertThat(lastHttResponse.cacheResponse()).isNotNull();
  }

  @Test public void cacheStale() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(1);

    Thread.sleep(TimeUnit.SECONDS.toMillis(3));

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);

    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheStaleBeforeNetwork() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(1);

    Thread.sleep(TimeUnit.SECONDS.toMillis(3));

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()).httpCachePolicy(HttpCachePolicy.NETWORK_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);

    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheStaleBeforeNetworkError() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(1);

    Thread.sleep(TimeUnit.SECONDS.toMillis(3));

    server.enqueue(new MockResponse().setResponseCode(504).setBody(""));
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNotNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheUpdate() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(1);
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    Thread.sleep(TimeUnit.SECONDS.toMillis(3));

    enqueueResponse("/HttpCacheTestAllPlanets2.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);
    assertThat(server.getRequestCount()).isEqualTo(2);
    checkCachedResponse("/HttpCacheTestAllPlanets2.json");
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();

    enqueueResponse("/HttpCacheTestAllPlanets2.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST.expireAfter(TIME_OUT_SECONDS, TimeUnit.SECONDS)))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS);

    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(lastHttResponse.networkResponse()).isNull();
    assertThat(lastHttResponse.cacheResponse()).isNotNull();
    checkCachedResponse("/HttpCacheTestAllPlanets2.json");
  }

  @Test public void fileSystemUnavailable() throws IOException, ApolloException {
    cacheStore.delegate = new DiskLruHttpCacheStore(new NoFileSystem(), new File("/cache/"), Integer.MAX_VALUE);
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkNoCachedResponse();
  }

  @Test public void fileSystemWriteFailure() throws IOException, ApolloException {
    FaultyHttpCacheStore faultyCacheStore = new FaultyHttpCacheStore(FileSystem.SYSTEM);
    cacheStore.delegate = faultyCacheStore;

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    faultyCacheStore.failStrategy(FaultyHttpCacheStore.FailStrategy.FAIL_HEADER_WRITE);
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkNoCachedResponse();

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    faultyCacheStore.failStrategy(FaultyHttpCacheStore.FailStrategy.FAIL_BODY_WRITE);
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkNoCachedResponse();
  }

  @Test public void fileSystemReadFailure() throws IOException, ApolloException {
    FaultyHttpCacheStore faultyCacheStore = new FaultyHttpCacheStore(inMemoryFileSystem);
    cacheStore.delegate = faultyCacheStore;

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    faultyCacheStore.failStrategy(FaultyHttpCacheStore.FailStrategy.FAIL_HEADER_READ);
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });

    assertThat(server.getRequestCount()).isEqualTo(2);

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    faultyCacheStore.failStrategy(FaultyHttpCacheStore.FailStrategy.FAIL_BODY_READ);

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertError(Exception.class);

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test public void expireAfterRead() throws IOException, ApolloException {
    enqueueResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY.expireAfterRead()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });

    checkNoCachedResponse();

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertError(Exception.class);

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient.query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void cacheNetworkError() throws IOException, ApolloException {
    server.enqueue(new MockResponse().setResponseCode(504).setBody(""));

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertError(Exception.class);

    checkNoCachedResponse();

    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
  }

  @Test public void networkFirst() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery()))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");

    enqueueResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors();
          }
        });

    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(lastHttResponse.networkResponse()).isNotNull();
    assertThat(lastHttResponse.cacheResponse()).isNull();
    checkCachedResponse("/HttpCacheTestAllPlanets.json");
  }

  @Test public void fromCacheFlag() throws Exception {
    enqueueResponse("/HttpCacheTestAllPlanets.json");
    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && !response.fromCache();
          }
        });

    enqueueResponse("/HttpCacheTestAllPlanets.json");

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && !response.fromCache();
          }
        });

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_ONLY))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && response.fromCache();
          }
        });

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && response.fromCache();
          }
        });

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && response.fromCache();
          }
        });

    Rx2Apollo.from(apolloClient
        .query(new AllPlanetsQuery())
        .httpCachePolicy(HttpCachePolicy.NETWORK_FIRST))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<AllPlanetsQuery.Data>>() {
          @Override public boolean test(Response<AllPlanetsQuery.Data> response) throws Exception {
            return !response.hasErrors() && response.fromCache();
          }
        });
  }

  private void enqueueResponse(String fileName) throws IOException {
    server.enqueue(mockResponse(fileName));
  }

  private void checkCachedResponse(String fileName) throws IOException {
    String cacheKey = ApolloServerInterceptor.cacheKey(lastHttRequest.body());
    okhttp3.Response response = apolloClient.cachedHttpResponse(cacheKey);
    assertThat(response).isNotNull();
    assertThat(response.body().source().readUtf8()).isEqualTo(Utils.readFileToString(getClass(), fileName));
    response.body().source().close();
  }

  private void checkNoCachedResponse() throws IOException {
    String cacheKey = lastHttRequest.header(HttpCache.CACHE_KEY_HEADER);
    okhttp3.Response cachedResponse = apolloClient.cachedHttpResponse(cacheKey);
    assertThat(cachedResponse).isNull();
  }

  private MockResponse mockResponse(String fileName) throws IOException {
    return new MockResponse().setChunkedBody(Utils.readFileToString(getClass(), fileName), 32);
  }

  private class TrackingInterceptor implements Interceptor {
    @Override public okhttp3.Response intercept(Chain chain) throws IOException {
      lastHttRequest = chain.request();
      lastHttResponse = chain.proceed(lastHttRequest);
      return lastHttResponse;
    }
  }
}
