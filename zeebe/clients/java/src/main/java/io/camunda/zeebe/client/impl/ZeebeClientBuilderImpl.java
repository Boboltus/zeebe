/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
 */
package io.camunda.zeebe.client.impl;

import static io.camunda.zeebe.client.ClientProperties.CA_CERTIFICATE_PATH;
import static io.camunda.zeebe.client.ClientProperties.DEFAULT_MESSAGE_TIME_TO_LIVE;
import static io.camunda.zeebe.client.ClientProperties.DEFAULT_REQUEST_TIMEOUT;
import static io.camunda.zeebe.client.ClientProperties.KEEP_ALIVE;
import static io.camunda.zeebe.client.ClientProperties.MAX_MESSAGE_SIZE;
import static io.camunda.zeebe.client.ClientProperties.OVERRIDE_AUTHORITY;
import static io.camunda.zeebe.client.ClientProperties.STREAM_ENABLED;
import static io.camunda.zeebe.client.ClientProperties.USE_DEFAULT_RETRY_POLICY;
import static io.camunda.zeebe.client.ClientProperties.USE_PLAINTEXT_CONNECTION;
import static io.camunda.zeebe.client.impl.BuilderUtils.appendProperty;
import static io.camunda.zeebe.client.impl.util.DataSizeUtil.ONE_MB;

import io.camunda.zeebe.client.ClientProperties;
import io.camunda.zeebe.client.CredentialsProvider;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.client.ZeebeClientConfiguration;
import io.camunda.zeebe.client.api.JsonMapper;
import io.camunda.zeebe.client.api.command.CommandWithCommunicationApiStep;
import io.camunda.zeebe.client.api.command.CommandWithTenantStep;
import io.camunda.zeebe.client.impl.oauth.OAuthCredentialsProviderBuilder;
import io.camunda.zeebe.client.impl.util.DataSizeUtil;
import io.camunda.zeebe.client.impl.util.Environment;
import io.grpc.ClientInterceptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;

public final class ZeebeClientBuilderImpl implements ZeebeClientBuilder, ZeebeClientConfiguration {

  public static final String PLAINTEXT_CONNECTION_VAR = "ZEEBE_INSECURE_CONNECTION";
  public static final String CA_CERTIFICATE_VAR = "ZEEBE_CA_CERTIFICATE_PATH";
  public static final String KEEP_ALIVE_VAR = "ZEEBE_KEEP_ALIVE";
  public static final String OVERRIDE_AUTHORITY_VAR = "ZEEBE_OVERRIDE_AUTHORITY";
  public static final String ZEEBE_CLIENT_WORKER_STREAM_ENABLED =
      "ZEEBE_CLIENT_WORKER_STREAM_ENABLED";
  public static final String DEFAULT_GATEWAY_ADDRESS = "0.0.0.0:26500";
  public static final String GATEWAY_REST_API_PORT_VAR = "ZEEBE_GATEWAY_REST_API_PORT";
  public static final int DEFAULT_GATEWAY_REST_API_PORT = 8080;
  public static final String DEFAULT_COMMUNICATION_API =
      CommandWithCommunicationApiStep.DEFAULT_COMMUNICATION_API;
  public static final String DEFAULT_COMMUNICATION_API_VAR = "ZEEBE_DEFAULT_COMMUNICATION_API";
  public static final String DEFAULT_TENANT_ID_VAR = "ZEEBE_DEFAULT_TENANT_ID";
  public static final String DEFAULT_JOB_WORKER_TENANT_IDS_VAR =
      "ZEEBE_DEFAULT_JOB_WORKER_TENANT_IDS";
  public static final String DEFAULT_JOB_WORKER_NAME = "default";
  public static final String USE_DEFAULT_RETRY_POLICY_VAR = "ZEEBE_CLIENT_USE_DEFAULT_RETRY_POLICY";
  private static final String TENANT_ID_LIST_SEPARATOR = ",";
  private boolean applyEnvironmentVariableOverrides = true;

  private final List<ClientInterceptor> interceptors = new ArrayList<>();
  private String gatewayAddress = DEFAULT_GATEWAY_ADDRESS;
  private String defaultCommunicationApi = DEFAULT_COMMUNICATION_API;
  private int gatewayRestApiPort = DEFAULT_GATEWAY_REST_API_PORT;
  private String defaultTenantId = CommandWithTenantStep.DEFAULT_TENANT_IDENTIFIER;
  private List<String> defaultJobWorkerTenantIds =
      Collections.singletonList(CommandWithTenantStep.DEFAULT_TENANT_IDENTIFIER);
  private int jobWorkerMaxJobsActive = 32;
  private int numJobWorkerExecutionThreads = 1;
  private String defaultJobWorkerName = DEFAULT_JOB_WORKER_NAME;
  private Duration defaultJobTimeout = Duration.ofMinutes(5);
  private Duration defaultJobPollInterval = Duration.ofMillis(100);
  private Duration defaultMessageTimeToLive = Duration.ofHours(1);
  private Duration defaultRequestTimeout = Duration.ofSeconds(10);
  private boolean usePlaintextConnection = false;
  private String certificatePath;
  private CredentialsProvider credentialsProvider;
  private Duration keepAlive = Duration.ofSeconds(45);
  private JsonMapper jsonMapper = new ZeebeObjectMapper();
  private String overrideAuthority;
  private int maxMessageSize = 4 * ONE_MB;
  private boolean streamEnabled = false;
  private ScheduledExecutorService jobWorkerExecutor;
  private boolean ownsJobWorkerExecutor;
  private boolean useDefaultRetryPolicy;

  @Override
  public String getGatewayAddress() {
    return gatewayAddress;
  }

  @Override
  public int getGatewayRestApiPort() {
    return gatewayRestApiPort;
  }

  @Override
  public String getDefaultCommunicationApi() {
    return defaultCommunicationApi;
  }

  @Override
  public boolean useRestApi() {
    return CommandWithCommunicationApiStep.REST.equals(defaultCommunicationApi);
  }

  @Override
  public String getDefaultTenantId() {
    return defaultTenantId;
  }

  @Override
  public List<String> getDefaultJobWorkerTenantIds() {
    return defaultJobWorkerTenantIds;
  }

  @Override
  public int getNumJobWorkerExecutionThreads() {
    return numJobWorkerExecutionThreads;
  }

  @Override
  public int getDefaultJobWorkerMaxJobsActive() {
    return jobWorkerMaxJobsActive;
  }

  @Override
  public String getDefaultJobWorkerName() {
    return defaultJobWorkerName;
  }

  @Override
  public Duration getDefaultJobTimeout() {
    return defaultJobTimeout;
  }

  @Override
  public Duration getDefaultJobPollInterval() {
    return defaultJobPollInterval;
  }

  @Override
  public Duration getDefaultMessageTimeToLive() {
    return defaultMessageTimeToLive;
  }

  @Override
  public Duration getDefaultRequestTimeout() {
    return defaultRequestTimeout;
  }

  @Override
  public boolean isPlaintextConnectionEnabled() {
    return usePlaintextConnection;
  }

  @Override
  public String getCaCertificatePath() {
    return certificatePath;
  }

  @Override
  public CredentialsProvider getCredentialsProvider() {
    return credentialsProvider;
  }

  @Override
  public Duration getKeepAlive() {
    return keepAlive;
  }

  @Override
  public List<ClientInterceptor> getInterceptors() {
    return interceptors;
  }

  @Override
  public JsonMapper getJsonMapper() {
    return jsonMapper;
  }

  @Override
  public String getOverrideAuthority() {
    return overrideAuthority;
  }

  @Override
  public int getMaxMessageSize() {
    return maxMessageSize;
  }

  @Override
  public ScheduledExecutorService jobWorkerExecutor() {
    return jobWorkerExecutor;
  }

  @Override
  public boolean ownsJobWorkerExecutor() {
    return ownsJobWorkerExecutor;
  }

  @Override
  public boolean getDefaultJobWorkerStreamEnabled() {
    return streamEnabled;
  }

  @Override
  public boolean useDefaultRetryPolicy() {
    return useDefaultRetryPolicy;
  }

  @Override
  public ZeebeClientBuilder withProperties(final Properties properties) {
    if (properties.containsKey(ClientProperties.APPLY_ENVIRONMENT_VARIABLES_OVERRIDES)) {
      applyEnvironmentVariableOverrides(
          Boolean.parseBoolean(
              properties.getProperty(ClientProperties.APPLY_ENVIRONMENT_VARIABLES_OVERRIDES)));
    }
    if (properties.containsKey(ClientProperties.GATEWAY_ADDRESS)) {
      gatewayAddress(properties.getProperty(ClientProperties.GATEWAY_ADDRESS));
    }
    if (properties.containsKey(ClientProperties.GATEWAY_REST_API_PORT)) {
      gatewayRestApiPort(
          Integer.parseInt(properties.getProperty(ClientProperties.GATEWAY_REST_API_PORT)));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_COMMUNICATION_API)) {
      defaultCommunicationApi(properties.getProperty(ClientProperties.DEFAULT_COMMUNICATION_API));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_TENANT_ID)) {
      defaultTenantId(properties.getProperty(ClientProperties.DEFAULT_TENANT_ID));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_JOB_WORKER_TENANT_IDS)) {
      final String tenantIdsList =
          properties.getProperty(ClientProperties.DEFAULT_JOB_WORKER_TENANT_IDS);
      final List<String> tenantIds = Arrays.asList(tenantIdsList.split(TENANT_ID_LIST_SEPARATOR));
      defaultJobWorkerTenantIds(tenantIds);
    }

    if (properties.containsKey(ClientProperties.JOB_WORKER_EXECUTION_THREADS)) {
      numJobWorkerExecutionThreads(
          Integer.parseInt(properties.getProperty(ClientProperties.JOB_WORKER_EXECUTION_THREADS)));
    }
    if (properties.containsKey(ClientProperties.JOB_WORKER_MAX_JOBS_ACTIVE)) {
      defaultJobWorkerMaxJobsActive(
          Integer.parseInt(properties.getProperty(ClientProperties.JOB_WORKER_MAX_JOBS_ACTIVE)));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_JOB_WORKER_NAME)) {
      defaultJobWorkerName(properties.getProperty(ClientProperties.DEFAULT_JOB_WORKER_NAME));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_JOB_TIMEOUT)) {
      defaultJobTimeout(
          Duration.ofMillis(
              Integer.parseInt(properties.getProperty(ClientProperties.DEFAULT_JOB_TIMEOUT))));
    }
    if (properties.containsKey(ClientProperties.DEFAULT_JOB_POLL_INTERVAL)) {
      defaultJobPollInterval(
          Duration.ofMillis(
              Integer.parseInt(
                  properties.getProperty(ClientProperties.DEFAULT_JOB_POLL_INTERVAL))));
    }
    if (properties.containsKey(DEFAULT_MESSAGE_TIME_TO_LIVE)) {
      defaultMessageTimeToLive(
          Duration.ofMillis(Long.parseLong(properties.getProperty(DEFAULT_MESSAGE_TIME_TO_LIVE))));
    }
    if (properties.containsKey(DEFAULT_REQUEST_TIMEOUT)) {
      defaultRequestTimeout(
          Duration.ofMillis(Long.parseLong(properties.getProperty(DEFAULT_REQUEST_TIMEOUT))));
    }
    if (properties.containsKey(USE_PLAINTEXT_CONNECTION)) {
      /**
       * The following condition is phrased in this particular way in order to be backwards
       * compatible with older versions of the software. In older versions the content of the
       * property was not interpreted. It was assumed to be true, whenever it was set. Because of
       * that, code examples in this code base set the flag to an empty string. By phrasing the
       * condition this way, the old code will still work with this new implementation. Only if
       * somebody deliberately sets the flag to false, the behavior will change
       */
      if (!"false".equalsIgnoreCase(properties.getProperty(USE_PLAINTEXT_CONNECTION))) {
        usePlaintext();
      }
    }
    if (properties.containsKey(CA_CERTIFICATE_PATH)) {
      caCertificatePath(properties.getProperty(CA_CERTIFICATE_PATH));
    }
    if (properties.containsKey(KEEP_ALIVE)) {
      keepAlive(properties.getProperty(KEEP_ALIVE));
    }
    if (properties.containsKey(OVERRIDE_AUTHORITY)) {
      overrideAuthority(properties.getProperty(OVERRIDE_AUTHORITY));
    }
    if (properties.containsKey(MAX_MESSAGE_SIZE)) {
      maxMessageSize(DataSizeUtil.parse(properties.getProperty(MAX_MESSAGE_SIZE)));
    }
    if (properties.containsKey(STREAM_ENABLED)) {
      defaultJobWorkerStreamEnabled(Boolean.parseBoolean(properties.getProperty(STREAM_ENABLED)));
    }
    if (properties.containsKey(USE_DEFAULT_RETRY_POLICY)) {
      useDefaultRetryPolicy(Boolean.parseBoolean(properties.getProperty(USE_DEFAULT_RETRY_POLICY)));
    }
    return this;
  }

  @Override
  public ZeebeClientBuilder applyEnvironmentVariableOverrides(
      final boolean applyEnvironmentVariableOverrides) {
    this.applyEnvironmentVariableOverrides = applyEnvironmentVariableOverrides;
    return this;
  }

  @Override
  public ZeebeClientBuilder gatewayAddress(final String gatewayAddress) {
    this.gatewayAddress = gatewayAddress;
    return this;
  }

  @Override
  public ZeebeClientBuilder gatewayRestApiPort(final int restApiPort) {
    if (restApiPort < 0 || restApiPort > 65535) {
      throw new IllegalArgumentException(
          String.format(
              "The REST API port must be a number between 0 and 65535, but was '%d'.",
              restApiPort));
    }
    gatewayRestApiPort = restApiPort;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultCommunicationApi(final String communicationApi) {
    if (!CommandWithCommunicationApiStep.AVAILABLE_COMMUNICATION_API.contains(communicationApi)) {
      throw new IllegalArgumentException(
          String.format(
              "The default communication API must be one of %s but was '%s'.",
              CommandWithCommunicationApiStep.AVAILABLE_COMMUNICATION_API, communicationApi));
    }
    defaultCommunicationApi = communicationApi;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultTenantId(final String tenantId) {
    defaultTenantId = tenantId;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobWorkerTenantIds(final List<String> tenantIds) {
    defaultJobWorkerTenantIds = tenantIds;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobWorkerMaxJobsActive(final int maxJobsActive) {
    jobWorkerMaxJobsActive = maxJobsActive;
    return this;
  }

  @Override
  public ZeebeClientBuilder numJobWorkerExecutionThreads(final int numSubscriptionThreads) {
    numJobWorkerExecutionThreads = numSubscriptionThreads;
    return this;
  }

  @Override
  public ZeebeClientBuilder jobWorkerExecutor(
      final ScheduledExecutorService executor, final boolean takeOwnership) {
    jobWorkerExecutor = executor;
    ownsJobWorkerExecutor = takeOwnership;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobWorkerName(final String workerName) {
    if (workerName != null) {
      defaultJobWorkerName = workerName;
    }
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobTimeout(final Duration timeout) {
    defaultJobTimeout = timeout;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobPollInterval(final Duration pollInterval) {
    defaultJobPollInterval = pollInterval;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultMessageTimeToLive(final Duration timeToLive) {
    defaultMessageTimeToLive = timeToLive;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultRequestTimeout(final Duration requestTimeout) {
    defaultRequestTimeout = requestTimeout;
    return this;
  }

  @Override
  public ZeebeClientBuilder usePlaintext() {
    usePlaintextConnection = true;
    return this;
  }

  @Override
  public ZeebeClientBuilder caCertificatePath(final String certificatePath) {
    this.certificatePath = certificatePath;
    return this;
  }

  @Override
  public ZeebeClientBuilder credentialsProvider(final CredentialsProvider credentialsProvider) {
    this.credentialsProvider = credentialsProvider;
    return this;
  }

  @Override
  public ZeebeClientBuilder keepAlive(final Duration keepAlive) {
    if (keepAlive.isNegative() || keepAlive.isZero()) {
      throw new IllegalArgumentException("The keep alive must be a positive number.");
    }

    this.keepAlive = keepAlive;
    return this;
  }

  @Override
  public ZeebeClientBuilder withInterceptors(final ClientInterceptor... interceptors) {
    this.interceptors.addAll(Arrays.asList(interceptors));
    return this;
  }

  @Override
  public ZeebeClientBuilder withJsonMapper(final JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
    return this;
  }

  @Override
  public ZeebeClientBuilder overrideAuthority(final String authority) {
    overrideAuthority = authority;
    return this;
  }

  @Override
  public ZeebeClientBuilder maxMessageSize(final int maxMessageSize) {
    this.maxMessageSize = maxMessageSize;
    return this;
  }

  @Override
  public ZeebeClientBuilder defaultJobWorkerStreamEnabled(final boolean streamEnabled) {
    this.streamEnabled = streamEnabled;
    return this;
  }

  @Override
  public ZeebeClientBuilder useDefaultRetryPolicy(final boolean useDefaultRetryPolicy) {
    this.useDefaultRetryPolicy = useDefaultRetryPolicy;
    return this;
  }

  @Override
  public ZeebeClient build() {
    if (applyEnvironmentVariableOverrides) {
      applyOverrides();
    }

    return new ZeebeClientImpl(this);
  }

  private void keepAlive(final String keepAlive) {
    keepAlive(Duration.ofMillis(Long.parseUnsignedLong(keepAlive)));
  }

  private void applyOverrides() {
    if (Environment.system().isDefined(PLAINTEXT_CONNECTION_VAR)) {
      usePlaintextConnection = Environment.system().getBoolean(PLAINTEXT_CONNECTION_VAR);
    }

    if (Environment.system().isDefined(CA_CERTIFICATE_VAR)) {
      caCertificatePath(Environment.system().get(CA_CERTIFICATE_VAR));
    }

    if (Environment.system().isDefined(KEEP_ALIVE_VAR)) {
      keepAlive(Environment.system().get(KEEP_ALIVE_VAR));
    }

    if (Environment.system().isDefined(OVERRIDE_AUTHORITY_VAR)) {
      overrideAuthority(Environment.system().get(OVERRIDE_AUTHORITY_VAR));
    }

    if (shouldUseDefaultCredentialsProvider()) {
      credentialsProvider = createDefaultCredentialsProvider();
    }

    if (Environment.system().isDefined(MAX_MESSAGE_SIZE)) {
      maxMessageSize(DataSizeUtil.parse(Environment.system().get(MAX_MESSAGE_SIZE)));
    }

    if (Environment.system().isDefined(GATEWAY_REST_API_PORT_VAR)) {
      gatewayRestApiPort(Integer.parseInt(Environment.system().get(GATEWAY_REST_API_PORT_VAR)));
    }

    if (Environment.system().isDefined(DEFAULT_COMMUNICATION_API_VAR)) {
      defaultCommunicationApi(Environment.system().get(DEFAULT_COMMUNICATION_API_VAR));
    }

    if (Environment.system().isDefined(DEFAULT_TENANT_ID_VAR)) {
      defaultTenantId(Environment.system().get(DEFAULT_TENANT_ID_VAR));
    }

    if (Environment.system().isDefined(DEFAULT_JOB_WORKER_TENANT_IDS_VAR)) {
      final String tenantIdsList = Environment.system().get(DEFAULT_JOB_WORKER_TENANT_IDS_VAR);
      final List<String> tenantIds = Arrays.asList(tenantIdsList.split(TENANT_ID_LIST_SEPARATOR));
      defaultJobWorkerTenantIds(tenantIds);
    }

    if (Environment.system().isDefined(ZEEBE_CLIENT_WORKER_STREAM_ENABLED)) {
      defaultJobWorkerStreamEnabled(
          Boolean.parseBoolean(Environment.system().get(ZEEBE_CLIENT_WORKER_STREAM_ENABLED)));
    }

    if (Environment.system().isDefined(USE_DEFAULT_RETRY_POLICY_VAR)) {
      useDefaultRetryPolicy(
          Boolean.parseBoolean(Environment.system().get(USE_DEFAULT_RETRY_POLICY_VAR)));
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();

    appendProperty(sb, "gatewayAddress", gatewayAddress);
    appendProperty(sb, "gatewayRestApiPort", gatewayRestApiPort);
    appendProperty(sb, "defaultTenantId", defaultTenantId);
    appendProperty(sb, "jobWorkerMaxJobsActive", jobWorkerMaxJobsActive);
    appendProperty(sb, "numJobWorkerExecutionThreads", numJobWorkerExecutionThreads);
    appendProperty(sb, "defaultJobWorkerName", defaultJobWorkerName);
    appendProperty(sb, "defaultJobTimeout", defaultJobTimeout);
    appendProperty(sb, "defaultJobPollInterval", defaultJobPollInterval);
    appendProperty(sb, "defaultMessageTimeToLive", defaultMessageTimeToLive);
    appendProperty(sb, "defaultRequestTimeout", defaultRequestTimeout);
    appendProperty(sb, "overrideAuthority", overrideAuthority);
    appendProperty(sb, "maxMessageSize", maxMessageSize);
    appendProperty(sb, "jobWorkerExecutor", jobWorkerExecutor);
    appendProperty(sb, "ownsJobWorkerExecutor", ownsJobWorkerExecutor);
    appendProperty(sb, "streamEnabled", streamEnabled);

    return sb.toString();
  }

  private boolean shouldUseDefaultCredentialsProvider() {
    return credentialsProvider == null
        && Environment.system().get(OAuthCredentialsProviderBuilder.OAUTH_ENV_CLIENT_ID) != null
        && Environment.system().get(OAuthCredentialsProviderBuilder.OAUTH_ENV_CLIENT_SECRET)
            != null;
  }

  private CredentialsProvider createDefaultCredentialsProvider() {
    final OAuthCredentialsProviderBuilder builder =
        CredentialsProvider.newCredentialsProviderBuilder();
    final int separatorIndex = gatewayAddress.lastIndexOf(':');
    if (separatorIndex > 0) {
      builder.audience(gatewayAddress.substring(0, separatorIndex));
    }

    return builder.build();
  }
}
