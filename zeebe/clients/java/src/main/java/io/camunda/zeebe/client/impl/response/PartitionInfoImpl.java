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
package io.camunda.zeebe.client.impl.response;

import io.camunda.zeebe.client.api.response.PartitionBrokerHealth;
import io.camunda.zeebe.client.api.response.PartitionBrokerRole;
import io.camunda.zeebe.client.api.response.PartitionInfo;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.Partition;
import io.camunda.zeebe.gateway.protocol.rest.Partition.HealthEnum;
import io.camunda.zeebe.gateway.protocol.rest.Partition.RoleEnum;
import java.util.Arrays;

public class PartitionInfoImpl implements PartitionInfo {

  private final int partitionId;
  private final PartitionBrokerRole role;
  private final PartitionBrokerHealth partitionBrokerHealth;

  public PartitionInfoImpl(final GatewayOuterClass.Partition partition) {
    partitionId = partition.getPartitionId();

    if (partition.getRole() == Partition.PartitionBrokerRole.LEADER) {
      role = PartitionBrokerRole.LEADER;
    } else if (partition.getRole() == Partition.PartitionBrokerRole.FOLLOWER) {
      role = PartitionBrokerRole.FOLLOWER;
    } else if (partition.getRole() == Partition.PartitionBrokerRole.INACTIVE) {
      role = PartitionBrokerRole.INACTIVE;
    } else {
      throw new RuntimeException(
          String.format(
              "Unexpected partition broker role %s, should be one of %s",
              partition.getRole(), Arrays.toString(PartitionBrokerRole.values())));
    }

    if (partition.getHealth() == Partition.PartitionBrokerHealth.HEALTHY) {
      partitionBrokerHealth = PartitionBrokerHealth.HEALTHY;
    } else if (partition.getHealth() == Partition.PartitionBrokerHealth.UNHEALTHY) {
      partitionBrokerHealth = PartitionBrokerHealth.UNHEALTHY;
    } else if (partition.getHealth() == Partition.PartitionBrokerHealth.DEAD) {
      partitionBrokerHealth = PartitionBrokerHealth.DEAD;
    } else {
      throw new RuntimeException(
          String.format(
              "Unexpected partition broker health %s, should be one of %s",
              partition.getHealth(), Arrays.toString(PartitionBrokerHealth.values())));
    }
  }

  public PartitionInfoImpl(final io.camunda.zeebe.gateway.protocol.rest.Partition httpPartition) {

    if (httpPartition.getPartitionId() == null) {
      throw new RuntimeException("Unexpected missing partition ID. A partition ID is required.");
    }
    partitionId = httpPartition.getPartitionId();

    if (httpPartition.getRole() == RoleEnum.LEADER) {
      role = PartitionBrokerRole.LEADER;
    } else if (httpPartition.getRole() == RoleEnum.FOLLOWER) {
      role = PartitionBrokerRole.FOLLOWER;
    } else if (httpPartition.getRole() == RoleEnum.INACTIVE) {
      role = PartitionBrokerRole.INACTIVE;
    } else {
      throw new RuntimeException(
          String.format(
              "Unexpected partition broker role %s, should be one of %s",
              httpPartition.getRole(), Arrays.toString(PartitionBrokerRole.values())));
    }

    if (httpPartition.getHealth() == HealthEnum.HEALTHY) {
      partitionBrokerHealth = PartitionBrokerHealth.HEALTHY;
    } else if (httpPartition.getHealth() == HealthEnum.UNHEALTHY) {
      partitionBrokerHealth = PartitionBrokerHealth.UNHEALTHY;
    } else if (httpPartition.getHealth() == HealthEnum.DEAD) {
      partitionBrokerHealth = PartitionBrokerHealth.DEAD;
    } else {
      throw new RuntimeException(
          String.format(
              "Unexpected partition broker health %s, should be one of %s",
              httpPartition.getHealth(), Arrays.toString(PartitionBrokerHealth.values())));
    }
  }

  @Override
  public int getPartitionId() {
    return partitionId;
  }

  @Override
  public PartitionBrokerRole getRole() {
    return role;
  }

  @Override
  public boolean isLeader() {
    return role == PartitionBrokerRole.LEADER;
  }

  @Override
  public PartitionBrokerHealth getHealth() {
    return partitionBrokerHealth;
  }

  @Override
  public String toString() {
    return "PartitionInfoImpl{"
        + "partitionId="
        + partitionId
        + ", role="
        + role
        + ", health="
        + partitionBrokerHealth
        + '}';
  }
}
