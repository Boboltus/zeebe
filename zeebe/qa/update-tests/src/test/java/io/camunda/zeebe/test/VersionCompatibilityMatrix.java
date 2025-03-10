/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.util.SemanticVersion;
import io.camunda.zeebe.util.VersionUtil;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Provides combinations of versions that should be compatible with each other. This matrix is used
 * in {@link RollingUpdateTest}.
 */
@SuppressWarnings("unused")
final class VersionCompatibilityMatrix {

  /**
   * Automatically chooses the matrix to use depending on the environment where tests are run.
   *
   * <ul>
   *   <li>Locally: {@link #fromPreviousMinorToCurrent()} for fast feedback.
   *   <li>CI: {@link #fromPreviousPatchesToCurrent()} for extended coverage without taking too much
   *       time.
   *   <li>Periodic tests: {@link #full()} for full coverage of all allowed upgrade paths.
   * </ul>
   */
  private static Stream<Arguments> auto() throws IOException, InterruptedException {
    if (System.getenv("ZEEBE_CI_CHECK_VERSION_COMPATIBILITY") != null) {
      return full();
    } else if (System.getenv("CI") != null) {
      return fromPreviousPatchesToCurrent();
    } else {
      return fromPreviousMinorToCurrent();
    }
  }

  private static Stream<Arguments> fromPreviousMinorToCurrent() {
    return Stream.of(Arguments.of(VersionUtil.getPreviousVersion(), "CURRENT"));
  }

  private static Stream<Arguments> fromPreviousPatchesToCurrent()
      throws IOException, InterruptedException {
    final var current = VersionUtil.getSemanticVersion().orElseThrow();
    return discoverVersions()
        .filter(version -> version.compareTo(current) < 0)
        .filter(version -> current.minor() - version.minor() <= 1)
        .map(version -> Arguments.of(version.toString(), "CURRENT"));
  }

  private static Stream<Arguments> full() {
    final var versions = discoverVersions().toList();
    return versions.stream()
        .filter(version -> version.minor() > 0)
        .flatMap(
            version1 ->
                versions.stream()
                    .filter(version2 -> version1.compareTo(version2) < 0)
                    .filter(version2 -> version2.minor() - version1.minor() <= 1)
                    .map(version2 -> Arguments.of(version1.toString(), version2.toString())));
  }

  /**
   * Discovers Zeebe versions that aren't pre-releases. Sourced from the GitHub API and can fail on
   * network issues. Includes all versions since 8.0.
   *
   * @see <a
   *     href="https://docs.github.com/en/rest/git/refs?apiVersion=2022-11-28#list-matching-references--parameters">GitHub
   *     API</a>
   */
  private static Stream<SemanticVersion> discoverVersions() {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ref(String ref) {
      SemanticVersion toSemanticVersion() {
        return SemanticVersion.parse(ref.substring("refs/tags/".length())).orElse(null);
      }
    }
    final var endpoint =
        URI.create("https://api.github.com/repos/camunda/zeebe/git/matching-refs/tags/8.");
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var retry =
          Retry.of(
              "github-api",
              RetryConfig.custom()
                  .maxAttempts(10)
                  .intervalFunction(IntervalFunction.ofExponentialBackoff())
                  .build());
      final var response =
          retry.executeCallable(
              () ->
                  httpClient.send(
                      HttpRequest.newBuilder().GET().uri(endpoint).build(),
                      BodyHandlers.ofByteArray()));
      final var refs = new ObjectMapper().readValue(response.body(), Ref[].class);
      return Stream.of(refs)
          .map(Ref::toSemanticVersion)
          .filter(version -> version != null && version.preRelease() == null);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }
}
