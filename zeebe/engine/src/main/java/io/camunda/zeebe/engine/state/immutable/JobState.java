/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.immutable;

import io.camunda.zeebe.protocol.impl.record.value.job.JobRecord;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import org.agrona.DirectBuffer;

public interface JobState {

  void forEachTimedOutEntry(long upperBound, BiPredicate<Long, JobRecord> callback);

  boolean exists(long jobKey);

  State getState(long key);

  boolean isInState(long key, State state);

  void forEachActivatableJobs(
      DirectBuffer type,
      final List<String> tenantIds,
      BiFunction<Long, JobRecord, Boolean> callback);

  JobRecord getJob(long key);

  JobRecord getJob(final long key, final Map<String, Object> authorizations);

  boolean jobDeadlineExists(final long jobKey, final long deadline);

  long findBackedOffJobs(final long timestamp, final BiPredicate<Long, JobRecord> callback);

  enum State {
    ACTIVATABLE((byte) 0),
    ACTIVATED((byte) 1),
    FAILED((byte) 2),
    NOT_FOUND((byte) 3),
    ERROR_THROWN((byte) 4);

    byte value;

    State(final byte value) {
      this.value = value;
    }
  }
}
