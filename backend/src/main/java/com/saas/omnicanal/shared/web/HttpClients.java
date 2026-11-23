package com.saas.omnicanal.shared.web;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Clientes HTTP con timeouts explícitos para APIs de terceros. */
public final class HttpClients {
    private HttpClients() {}

    public static RestClient.Builder builder(Duration connect, Duration read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory);
    }

    public static RestClient.Builder standard() {
        return builder(Duration.ofSeconds(5), Duration.ofSeconds(15));
    }
}
