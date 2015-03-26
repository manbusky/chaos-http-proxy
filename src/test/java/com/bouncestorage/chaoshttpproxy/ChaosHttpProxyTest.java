/*
 * Copyright 2015 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bouncestorage.chaoshttpproxy;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;
import java.net.URI;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChaosHttpProxyTest {
    private static final Logger logger = LoggerFactory.getLogger(
            ChaosHttpProxyTest.class);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    // TODO: configurable
    private URI proxyEndpoint = URI.create("http://127.0.0.1:0");
    private URI httpBinEndpoint = URI.create("http://127.0.0.1:0");

    private ChaosHttpProxy proxy;
    private HttpBin httpBin;
    private HttpClient client;

    @Before
    public void setUp() throws Exception {
        proxy = new ChaosHttpProxy(proxyEndpoint,
                Suppliers.ofInstance(Failure.SUCCESS));
        proxy.start();

        // reset endpoint to handle zero port
        proxyEndpoint = new URI(proxyEndpoint.getScheme(),
                proxyEndpoint.getUserInfo(), proxyEndpoint.getHost(),
                proxy.getPort(), proxyEndpoint.getPath(),
                proxyEndpoint.getQuery(), proxyEndpoint.getFragment());
        logger.debug("ChaosHttpProxy listening on {}", proxyEndpoint);

        httpBin = new HttpBin(httpBinEndpoint);
        httpBin.start();

        // reset endpoint to handle zero port
        httpBinEndpoint = new URI(httpBinEndpoint.getScheme(),
                httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
                httpBin.getPort(), httpBinEndpoint.getPath(),
                httpBinEndpoint.getQuery(), httpBinEndpoint.getFragment());
        logger.debug("HttpBin listening on {}", httpBinEndpoint);

        client = new HttpClient();
        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxyConfig.getProxies().add(new HttpProxy(proxyEndpoint.getHost(),
                proxyEndpoint.getPort()));
        client.start();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (httpBin != null) {
            httpBin.stop();
        }
        if (proxy != null) {
            proxy.stop();
        }
    }

    @Test
    public void testHttpGet200() throws Exception {
        assertThat(client.GET(httpBinEndpoint + "/status/200").getStatus())
                .as("status").isEqualTo(200);
    }

    @Test
    public void testHttpGet500() throws Exception {
        assertThat(client.GET(httpBinEndpoint + "/status/500").getStatus())
                .as("status").isEqualTo(500);
    }

    @Test
    public void testHttpGetHeaders() throws Exception {
        String headerName = "X-Test-Header";
        String headerValue = "Test-Value";
        ContentResponse response = client.GET(
                httpBinEndpoint + "/response-headers?" + headerName +
                "=" + headerValue);
        assertThat(response.getHeaders().getField(headerName).getValue())
                .isEqualTo(headerValue);
    }

    @Test
    public void testHttpPost() throws Exception {
        assertThat(client.POST(httpBinEndpoint + "/post")
                .send().getStatus()).as("status").isEqualTo(200);
    }

    @Test
    public void testHttpPut() throws Exception {
        client.newRequest(httpBinEndpoint + "/put")
                .content(new BytesContentProvider(new byte[1]))
                .send();
    }

    @Test
    public void testHttpGet200Failure() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(Failure.HTTP_500));
        assertThat(client.GET(httpBinEndpoint + "/status/200").getStatus())
                .as("status").isEqualTo(500);
    }

    @Test
    public void testHttpGetFullData() throws Exception {
        client.GET(httpBinEndpoint + "/get");
    }

    @Test
    public void testHttpGetPartialData() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(Failure.PARTIAL_DATA));
        thrown.expect(ExecutionException.class);
        thrown.expectCause(CoreMatchers.isA(EOFException.class));
        client.GET(httpBinEndpoint + "/get");
    }

    @Test
    public void testHttpGetSlowResponse() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(Failure.SLOW_RESPONSE));
        ContentResponse response = client.GET(httpBinEndpoint + "/get");
        assertThat(response.getContentAsString()).isEqualTo("Hello, world!");
    }

    @Test
    public void testHttpGetTimeout() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(Failure.TIMEOUT));
        thrown.expect(TimeoutException.class);
        client.newRequest(httpBinEndpoint + "/status/200")
                .timeout(1, TimeUnit.SECONDS)
                .send();
    }

    @Test
    public void testHttpGetCorruptContentMD5() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(
                Failure.CORRUPT_RESPONSE_CONTENT_MD5));
        String headerName = HttpHeaders.CONTENT_MD5;
        String headerValue = "1B2M2Y8AsgTpgAmY7PhCfg==";
        String corruptValue = "AAAAAAAAAAAAAAAAAAAAAA==";
        ContentResponse response = client.GET(
                httpBinEndpoint + "/response-headers?" + headerName +
                "=" + headerValue);
        assertThat(response.getHeaders().getField(headerName).getValue())
                .isEqualTo(corruptValue);
    }

    @Test
    public void testPermanentRedirectNoFollow() throws Exception {
        proxy.setFailureSupplier(Suppliers.ofInstance(Failure.HTTP_301));
        client.setFollowRedirects(false);
        assertThat(client.GET(httpBinEndpoint + "/status/200").getStatus())
                .as("status").isEqualTo(301);
    }

    @Test
    public void testPermanentRedirectFollow() throws Exception {
        proxy.setFailureSupplier(new SupplierFromIterable<>(
                ImmutableList.of(Failure.HTTP_301, Failure.SUCCESS)));
        assertThat(client.GET(httpBinEndpoint + "/status/200").getStatus())
                .as("status").isEqualTo(200);
    }

    /** Supplier whose elements are provided by an Iterable. */
    private static class SupplierFromIterable<T> implements Supplier<T> {
        private final Iterator<T> iterator;

        SupplierFromIterable(Iterable<T> iterable) {
            this.iterator = requireNonNull(iterable).iterator();
        }

        @Override
        public T get() {
            return iterator.next();
        }
    }
}