/*
 * Copyright 2004 Sun Microsystems, Inc.
 *
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
 *
 */
package com.rometools.test;

import com.rometools.fetcher.FeedFetcher;
import com.rometools.fetcher.impl.FeedFetcherCache;
import com.rometools.fetcher.impl.HttpClientFeedFetcher;
import com.rometools.fetcher.impl.HttpClientFeedFetcher.ContextSupplier;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;

/**
 * @author Nick Lothian
 */
public class HttpClientFeedFetcherTest extends AbstractJettyTest {

    public HttpClientFeedFetcherTest(final String s) {
        super(s);
    }

    /**
     * @see com.rometools.rome.fetcher.impl.AbstractJettyTest#getFeedFetcher()
     */
    @Override
    protected FeedFetcher getFeedFetcher() {
        return new HttpClientFeedFetcher();
    }

    @Override
    protected FeedFetcher getFeedFetcher(final FeedFetcherCache cache) {
        HttpClientFeedFetcher fetcher = new HttpClientFeedFetcher();
        fetcher.setFeedInfoCache(cache);

        return fetcher;
    }

    /**
     * @see com.rometools.rome.fetcher.impl.AbstractJettyTest#getAuthenticatedFeedFetcher()
     */
    @Override
    public FeedFetcher getAuthenticatedFeedFetcher() {
        HttpClientFeedFetcher fetcher = new HttpClientFeedFetcher();
        fetcher.setContextSupplier(new ContextSupplier() {
            @Override
            public HttpClientContext getContext(HttpHost host) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("username:password"));
                HttpClientContext context = HttpClientContext.create();
                context.setCredentialsProvider(credentialsProvider);

                return context;
            }
        });

        return fetcher;
    }
}
