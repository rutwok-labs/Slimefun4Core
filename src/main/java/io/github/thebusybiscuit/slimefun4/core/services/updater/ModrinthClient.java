package io.github.thebusybiscuit.slimefun4.core.services.updater;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;

import io.github.thebusybiscuit.slimefun4.utils.JsonUtils;

/**
 * HTTP client wrapper for Modrinth API and file downloads with bounded retries.
 */
public final class ModrinthClient {

    private static final String USER_AGENT = "SlimefunCoreV4.0-Updater";
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(1500);

    private final HttpClient client;
    private final Duration timeout;
    private final int retries;

    public ModrinthClient(@Nonnull Duration timeout, int retries) {
        this.timeout = timeout;
        this.retries = Math.max(0, retries);
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public @Nonnull JsonArray fetchVersions(@Nonnull URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = sendWithRetries(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonUtils.parseString(response.body()).getAsJsonArray();
    }

    public void downloadFile(@Nonnull URI uri, @Nonnull Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        sendWithRetries(request, HttpResponse.BodyHandlers.ofFile(target));
    }

    private <T> @Nonnull HttpResponse<T> sendWithRetries(
        @Nonnull HttpRequest request,
        @Nonnull HttpResponse.BodyHandler<T> bodyHandler
    ) throws IOException, InterruptedException {
        IOException lastException = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            HttpResponse<T> response;

            try {
                response = client.send(request, bodyHandler);
            } catch (IOException x) {
                lastException = x;

                if (attempt == retries) {
                    throw x;
                }

                Thread.sleep(DEFAULT_RETRY_BACKOFF.toMillis());
                continue;
            }

            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return response;
            }

            if (!shouldRetry(statusCode) || attempt == retries) {
                throw new IOException("HTTP " + statusCode + " from " + request.uri());
            }

            sleepBeforeRetry(response);
        }

        throw lastException != null ? lastException : new IOException("Request failed without a response: " + request.uri());
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode == 503;
    }

    private void sleepBeforeRetry(@Nonnull HttpResponse<?> response) throws InterruptedException {
        if (response.statusCode() == 429) {
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                try {
                    Thread.sleep(Duration.ofSeconds(Long.parseLong(retryAfter.get().trim())).toMillis());
                    return;
                } catch (NumberFormatException ignored) {
                    // Fall back to the default backoff for non-numeric Retry-After values.
                }
            }
        }

        Thread.sleep(DEFAULT_RETRY_BACKOFF.toMillis());
    }
}
