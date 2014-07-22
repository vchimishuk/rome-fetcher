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
package com.rometools.fetcher.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import com.rometools.fetcher.FetcherEvent;
import com.rometools.fetcher.FetcherException;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.HttpClients;

/**
 * @author Nick Lothian
 */
public class HttpClientFeedFetcher extends AbstractFeedFetcher {

    private ContextSupplier contextSupplier;
    private FeedFetcherCache feedInfoCache;
    private volatile HttpClientMethodCallbackIntf httpClientMethodCallback;
    private HttpClient client;

    public HttpClientFeedFetcher() {
        client = HttpClients.createDefault();
    }

    public HttpClientFeedFetcher(HttpClient client) {
        this.client = client;
    }

    /**
     * @param credentialSupplier The credentialSupplier to set.
     */
    public synchronized void setContextSupplier(final ContextSupplier contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    /**
     * @return Returns the credentialSupplier.
     */
    public synchronized ContextSupplier getContextSupplier() {
        return contextSupplier;
    }

    /**
     * @param feedInfoCache the feedInfoCache to set
     */
    public synchronized void setFeedInfoCache(final FeedFetcherCache feedInfoCache) {
        this.feedInfoCache = feedInfoCache;
    }

    /**
     * @return the feedInfoCache.
     */
    public synchronized FeedFetcherCache getFeedInfoCache() {
        return feedInfoCache;
    }

    public synchronized void setHttpClientMethodCallback(final HttpClientMethodCallbackIntf httpClientMethodCallback) {
        this.httpClientMethodCallback = httpClientMethodCallback;
    }

    public HttpClientMethodCallbackIntf getHttpClientMethodCallback() {
        return httpClientMethodCallback;
    }

    @Override
    public SyndFeed retrieveFeed(final URL url) throws IllegalArgumentException, IOException, FeedException, FetcherException {
        return this.retrieveFeed(getUserAgent(), url);
    }

    /**
     * @see com.rometools.fetcher.FeedFetcher#retrieveFeed(java.net.URL)
     */
    @Override
    public SyndFeed retrieveFeed(final String userAgent, final URL feedUrl) throws IllegalArgumentException, IOException, FeedException, FetcherException {

        if (feedUrl == null) {
            throw new IllegalArgumentException("null is not a valid URL");
        }

        System.setProperty("httpclient.useragent", userAgent);

        final String urlStr = feedUrl.toString();

        final HttpGet method = new HttpGet(urlStr);
        method.addHeader("Accept-Encoding", "gzip");
        method.addHeader("User-Agent", userAgent);

        if (httpClientMethodCallback != null) {
            synchronized (httpClientMethodCallback) {
                httpClientMethodCallback.afterHttpClientMethodCreate(method);
            }
        }

        final FeedFetcherCache cache = getFeedInfoCache();

        if (cache != null) {
            // retrieve feed
            try {
                if (isUsingDeltaEncoding()) {
                    method.setHeader("A-IM", "feed");
                }

                // get the feed info from the cache
                // Note that syndFeedInfo will be null if it is not in the cache
                SyndFeedInfo syndFeedInfo = cache.getFeedInfo(feedUrl);

                if (syndFeedInfo != null) {
                    method.setHeader("If-None-Match", syndFeedInfo.getETag());

                    if (syndFeedInfo.getLastModified() instanceof String) {
                        method.setHeader("If-Modified-Since", (String) syndFeedInfo.getLastModified());
                    }
                }

                HttpResponse response = executeMethod(method);
                int statusCode = response.getStatusLine().getStatusCode();

                fireEvent(FetcherEvent.EVENT_TYPE_FEED_POLLED, urlStr);
                handleErrorCodes(statusCode);

                SyndFeed feed = getFeed(syndFeedInfo, urlStr, response, statusCode);

                syndFeedInfo = buildSyndFeedInfo(feedUrl, urlStr, response, feed, statusCode);

                cache.setFeedInfo(new URL(urlStr), syndFeedInfo);

                // the feed may have been modified to pick up cached values
                // (eg - for delta encoding)
                feed = syndFeedInfo.getSyndFeed();

                return feed;
            } finally {
                method.releaseConnection();
            }
        } else {
            // cache is not in use
            try {
                HttpResponse response = executeMethod(method);
                int statusCode = response.getStatusLine().getStatusCode();

                fireEvent(FetcherEvent.EVENT_TYPE_FEED_POLLED, urlStr);
                handleErrorCodes(statusCode);

                return getFeed(null, urlStr, response, statusCode);
            } finally {
                method.releaseConnection();
            }
        }
    }

    private SyndFeed getFeed(final SyndFeedInfo syndFeedInfo, final String urlStr, final HttpResponse response, final int statusCode) throws IOException,
            FetcherException, FeedException {

        if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED && syndFeedInfo != null) {
            fireEvent(FetcherEvent.EVENT_TYPE_FEED_UNCHANGED, urlStr);
            return syndFeedInfo.getSyndFeed();
        }

        SyndFeed feed;

        try {
            feed = retrieveFeed(urlStr, response);
        } catch (HttpException e) {
            throw new FetcherException(e.getMessage(), e);
        }

        fireEvent(FetcherEvent.EVENT_TYPE_FEED_RETRIEVED, urlStr, feed);

        return feed;
    }

    /**
     * @param feedUrl
     * @param urlStr
     * @param method
     * @param feed
     * @return
     * @throws MalformedURLException
     */
    private SyndFeedInfo buildSyndFeedInfo(final URL feedUrl, final String urlStr, final HttpResponse response, SyndFeed feed, final int statusCode)
            throws MalformedURLException {

        SyndFeedInfo syndFeedInfo;
        syndFeedInfo = new SyndFeedInfo();

        // this may be different to feedURL because of 3XX redirects
        syndFeedInfo.setUrl(new URL(urlStr));
        syndFeedInfo.setId(feedUrl.toString());

        final Header imHeader = response.getFirstHeader("IM");

        if (imHeader != null && imHeader.getValue().indexOf("feed") >= 0 && isUsingDeltaEncoding()) {
            final FeedFetcherCache cache = getFeedInfoCache();

            if (cache != null && statusCode == 226) {
                // client is setup to use http delta encoding and the server supports it and has
                // returned a delta encoded response
                // This response only includes new items
                final SyndFeedInfo cachedInfo = cache.getFeedInfo(feedUrl);

                if (cachedInfo != null) {
                    final SyndFeed cachedFeed = cachedInfo.getSyndFeed();

                    // set the new feed to be the orginal feed plus the new items
                    feed = combineFeeds(cachedFeed, feed);
                }
            }
        }

        final Header lastModifiedHeader = response.getFirstHeader("Last-Modified");

        if (lastModifiedHeader != null) {
            syndFeedInfo.setLastModified(lastModifiedHeader.getValue());
        }

        final Header eTagHeader = response.getFirstHeader("ETag");

        if (eTagHeader != null) {
            syndFeedInfo.setETag(eTagHeader.getValue());
        }

        syndFeedInfo.setSyndFeed(feed);

        return syndFeedInfo;
    }

    /**
     * @param client
     * @param urlStr
     * @param method
     * @return
     * @throws IOException
     * @throws HttpException
     * @throws FetcherException
     * @throws FeedException
     */
    private SyndFeed retrieveFeed(final String urlStr, final HttpResponse response) throws IOException, HttpException, FetcherException, FeedException {

        InputStream stream = null;

        if (response.getFirstHeader("Content-Encoding") != null && "gzip".equalsIgnoreCase(response.getFirstHeader("Content-Encoding").getValue())) {
            stream = new GZIPInputStream(response.getEntity().getContent());
        } else {
            stream = response.getEntity().getContent();
        }

        try {
            XmlReader reader = null;

            if (response.getFirstHeader("Content-Type") != null) {
                reader = new XmlReader(stream, response.getFirstHeader("Content-Type").getValue(), true);
            } else {
                reader = new XmlReader(stream, true);
            }

            final SyndFeedInput syndFeedInput = new SyndFeedInput();
            syndFeedInput.setPreserveWireFeed(isPreserveWireFeed());

            return syndFeedInput.build(reader);
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private HttpResponse executeMethod(HttpRequestBase method) throws ClientProtocolException, IOException {
        HttpResponse response;

        if (getContextSupplier() != null) {
            HttpHost host = new HttpHost(method.getURI().getHost(), method.getURI().getPort());
            response = client.execute(method, getContextSupplier().getContext(host));
        } else {
            response = client.execute(method);
        }

        return response;
    }

    public interface ContextSupplier {
        public HttpClientContext getContext(HttpHost host);
    }

    public interface HttpClientMethodCallbackIntf {
        /**
         * Allows access to the underlying HttpClient HttpMethod object. Note that in most cases,
         * method.setRequestHeader(String, String) is what you want to do (rather than
         * method.addRequestHeader(String, String))
         *
         * @param method
         */
        public void afterHttpClientMethodCreate(HttpRequestBase method);
    }

}
