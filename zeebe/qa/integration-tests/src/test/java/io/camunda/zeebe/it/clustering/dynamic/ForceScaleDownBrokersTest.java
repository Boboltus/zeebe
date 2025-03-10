/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.clustering.dynamic;

import static io.camunda.zeebe.it.clustering.dynamic.Utils.assertThatAllJobsCanBeCompleted;
import static io.camunda.zeebe.it.clustering.dynamic.Utils.createInstanceWithAJobOnAllPartitions;

import io.atomix.cluster.MemberId;
import io.camunda.zeebe.qa.util.actuator.ClusterActuator;
import io.camunda.zeebe.qa.util.cluster.TestApplication;
import io.camunda.zeebe.qa.util.cluster.TestCluster;
import io.camunda.zeebe.qa.util.topology.ClusterActuatorAssert;
import io.camunda.zeebe.test.util.junit.AutoCloseResources;
import java.time.Duration;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@AutoCloseResources
@Timeout(2 * 60)
class ForceScaleDownBrokersTest {
  private static final int PARTITIONS_COUNT = 1;
  private static final String JOB_TYPE = "job";

  @ParameterizedTest
  @CsvSource({"2, 1", "4, 2", "6, 3"})
  void shouldForceRemoveBrokers(final int oldClusterSize, final int newClusterSize) {
    // given
    try (final var cluster = createCluster(oldClusterSize, oldClusterSize);
        final var zeebeClient = cluster.availableGateway().newClientBuilder().build()) {
      final var brokersToShutdown =
          IntStream.range(newClusterSize, oldClusterSize)
              .mapToObj(String::valueOf)
              .map(MemberId::from)
              .map(cluster.brokers()::get)
              .toList();

      final var brokersToKeep = IntStream.range(0, newClusterSize).boxed().toList();

      final var createdInstances =
          createInstanceWithAJobOnAllPartitions(zeebeClient, JOB_TYPE, PARTITIONS_COUNT);

      // when
      brokersToShutdown.forEach(TestApplication::close);
      final var response =
          ClusterActuator.of(cluster.availableGateway()).scaleBrokers(brokersToKeep, false, true);
      Awaitility.await()
          .timeout(Duration.ofMinutes(2))
          .untilAsserted(
              () -> ClusterActuatorAssert.assertThat(cluster).hasAppliedChanges(response));

      // then
      brokersToKeep.forEach(
          brokerId -> ClusterActuatorAssert.assertThat(cluster).brokerHasPartition(brokerId, 1));
      brokersToShutdown.forEach(
          brokerId ->
              ClusterActuatorAssert.assertThat(cluster)
                  .doesNotHaveBroker(brokerId.brokerConfig().getCluster().getNodeId()));

      // Changes are reflected in the topology returned by grpc query
      cluster.awaitCompleteTopology(
          newClusterSize, PARTITIONS_COUNT, newClusterSize, Duration.ofSeconds(20));

      assertThatAllJobsCanBeCompleted(createdInstances, zeebeClient, JOB_TYPE);
    }
  }

  TestCluster createCluster(final int clusterSize, final int replicationFactor) {
    final var cluster =
        TestCluster.builder()
            .useRecordingExporter(true)
            // Use standalone gateway because we will be shutting down some of the brokers
            // during the test
            .withGatewaysCount(1)
            .withEmbeddedGateway(false)
            .withBrokersCount(clusterSize)
            .withPartitionsCount(PARTITIONS_COUNT)
            .withReplicationFactor(replicationFactor)
            .withGatewayConfig(
                g ->
                    g.gatewayConfig()
                        .getCluster()
                        .getMembership()
                        // Decrease the timeouts for fast convergence of gateway topology. When the
                        // broker is shutdown, the topology update takes at least 10 seconds with
                        // the default values.
                        .setSyncInterval(Duration.ofSeconds(1))
                        .setFailureTimeout(Duration.ofSeconds(2)))
            .build()
            .start();
    cluster.awaitCompleteTopology();
    return cluster;
  }
}
