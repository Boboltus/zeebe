/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.qa.util.cluster;

import io.atomix.cluster.MemberId;
import io.camunda.zeebe.broker.StandaloneBroker;
import io.camunda.zeebe.broker.shared.BrokerConfiguration.BrokerProperties;
import io.camunda.zeebe.broker.shared.WorkingDirectoryConfiguration.WorkingDirectory;
import io.camunda.zeebe.broker.system.configuration.ExporterCfg;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.qa.util.actuator.BrokerHealthActuator;
import io.camunda.zeebe.qa.util.actuator.GatewayHealthActuator;
import io.camunda.zeebe.qa.util.actuator.HealthActuator;
import io.camunda.zeebe.shared.Profile;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.socket.SocketUtil;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.util.unit.DataSize;

/** Represents an instance of the {@link StandaloneBroker} Spring application. */
@SuppressWarnings("UnusedReturnValue")
public final class TestStandaloneBroker extends TestSpringApplication<TestStandaloneBroker>
    implements TestGateway<TestStandaloneBroker> {

  private static final String RECORDING_EXPORTER_ID = "recordingExporter";
  private final BrokerProperties config;

  public TestStandaloneBroker() {
    super(StandaloneBroker.class);

    config = new BrokerProperties();

    config.getNetwork().getCommandApi().setPort(SocketUtil.getNextAddress().getPort());
    config.getNetwork().getInternalApi().setPort(SocketUtil.getNextAddress().getPort());
    config.getGateway().getNetwork().setPort(SocketUtil.getNextAddress().getPort());

    // set a smaller default log segment size since we pre-allocate, which might be a lot in tests
    // for local development; also lower the watermarks for local testing
    config.getData().setLogSegmentSize(DataSize.ofMegabytes(16));
    config.getData().getDisk().getFreeSpace().setProcessing(DataSize.ofMegabytes(128));
    config.getData().getDisk().getFreeSpace().setReplication(DataSize.ofMegabytes(64));

    //noinspection resource
    withBean("config", config, BrokerProperties.class);
  }

  @Override
  public int mappedPort(final TestZeebePort port) {
    return switch (port) {
      case COMMAND -> config.getNetwork().getCommandApi().getPort();
      case GATEWAY -> config.getGateway().getNetwork().getPort();
      case CLUSTER -> config.getNetwork().getInternalApi().getPort();
      default -> super.mappedPort(port);
    };
  }

  @Override
  protected SpringApplicationBuilder createSpringBuilder() {
    return super.createSpringBuilder().profiles(Profile.BROKER.getId());
  }

  @Override
  public TestStandaloneBroker self() {
    return this;
  }

  @Override
  public MemberId nodeId() {
    return MemberId.from(String.valueOf(config.getCluster().getNodeId()));
  }

  @Override
  public String host() {
    return config.getNetwork().getHost();
  }

  @Override
  public HealthActuator healthActuator() {
    return brokerHealth();
  }

  @Override
  public boolean isGateway() {
    return config.getGateway().isEnable();
  }

  @Override
  public String gatewayAddress() {
    if (!isGateway()) {
      throw new IllegalStateException(
          "Expected to get the gateway address for this broker, but the embedded gateway is not enabled");
    }

    return TestGateway.super.gatewayAddress();
  }

  @Override
  public GatewayHealthActuator gatewayHealth() {
    throw new UnsupportedOperationException("Brokers do not support the gateway health indicators");
  }

  @Override
  public TestStandaloneBroker withGatewayConfig(final Consumer<GatewayCfg> modifier) {
    modifier.accept(config.getGateway());
    return this;
  }

  @Override
  public GatewayCfg gatewayConfig() {
    return config.getGateway();
  }

  @Override
  public ZeebeClientBuilder newClientBuilder() {
    if (!isGateway()) {
      throw new IllegalStateException(
          "Cannot create a new client for this broker, as it does not have an embedded gateway");
    }

    return TestGateway.super.newClientBuilder();
  }

  /** Returns the broker configuration */
  public BrokerProperties brokerConfig() {
    return config;
  }

  /**
   * Modifies the broker configuration. Will still mutate the configuration if the broker is
   * started, but likely has no effect until it's restarted.
   */
  public TestStandaloneBroker withBrokerConfig(final Consumer<BrokerProperties> modifier) {
    modifier.accept(config);
    return this;
  }

  /**
   * Returns the health actuator for this broker. You can use this to check for liveness, readiness,
   * and startup.
   */
  public BrokerHealthActuator brokerHealth() {
    return BrokerHealthActuator.ofAddress(monitoringAddress());
  }

  /**
   * Enables/disables usage of the recording exporter using {@link #RECORDING_EXPORTER_ID} as its
   * unique ID.
   *
   * @param useRecordingExporter if true, will enable the exporter; if false, will remove it from
   *     the config
   * @return itself for chaining
   */
  public TestStandaloneBroker withRecordingExporter(final boolean useRecordingExporter) {
    if (!useRecordingExporter) {
      config.getExporters().remove(RECORDING_EXPORTER_ID);
      return this;
    }

    return withExporter(
        RECORDING_EXPORTER_ID, cfg -> cfg.setClassName(RecordingExporter.class.getName()));
  }

  /**
   * Adds or replaces a new exporter with the given ID. If it was already existing, the existing
   * configuration is passed to the modifier. If it's new, a blank configuration is passed.
   *
   * @param id the ID of the exporter
   * @param modifier a configuration function
   * @return itself for chaining
   */
  public TestStandaloneBroker withExporter(final String id, final Consumer<ExporterCfg> modifier) {
    final var exporterConfig =
        config.getExporters().computeIfAbsent(id, ignored -> new ExporterCfg());
    modifier.accept(exporterConfig);

    return this;
  }

  /**
   * Sets the broker's working directory, aka its data directory. If a path is given, the broker
   * will not delete it on shutdown.
   *
   * @param directory path to the broker's root data directory
   * @return itself for chaining
   */
  public TestStandaloneBroker withWorkingDirectory(final Path directory) {
    return withBean(
        "workingDirectory", new WorkingDirectory(directory, false), WorkingDirectory.class);
  }
}
