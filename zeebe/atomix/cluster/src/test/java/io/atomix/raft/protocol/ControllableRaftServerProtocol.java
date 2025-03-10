/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.protocol;

import io.atomix.cluster.MemberId;
import io.camunda.zeebe.util.collection.Tuple;
import java.net.ConnectException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controllable raft server protocol. The messages are delivered only when explicitly instructed and
 * messages can be dropped.
 */
public class ControllableRaftServerProtocol implements RaftServerProtocol {
  private static final Logger LOG = LoggerFactory.getLogger(ControllableRaftServerProtocol.class);

  private Function<ConfigureRequest, CompletableFuture<ConfigureResponse>> configureHandler;
  private Function<ReconfigureRequest, CompletableFuture<ReconfigureResponse>> reconfigureHandler;
  private Function<ForceConfigureRequest, CompletableFuture<ForceConfigureResponse>>
      forceConfigureHandler;
  private Function<JoinRequest, CompletableFuture<JoinResponse>> joinHandler;
  private Function<LeaveRequest, CompletableFuture<LeaveResponse>> leaveHandler;

  private Function<InstallRequest, CompletableFuture<InstallResponse>> installHandler;
  private Function<TransferRequest, CompletableFuture<TransferResponse>> transferHandler;
  private Function<PollRequest, CompletableFuture<PollResponse>> pollHandler;
  private Function<VoteRequest, CompletableFuture<VoteResponse>> voteHandler;
  private Function<VersionedAppendRequest, CompletableFuture<AppendResponse>> appendHandler;
  private final Map<MemberId, ControllableRaftServerProtocol> servers;
  // Incoming messages to each member
  private final Map<MemberId, Queue<Tuple<Runnable, CompletableFuture<?>>>> messageQueue;
  private final MemberId localMemberId;
  private final Map<CompletableFuture<?>, Long> timeoutQueue = new HashMap<>();
  private long currentTime = 0;
  private final long requestTimeoutMillis = Duration.ofSeconds(5).toMillis();

  public ControllableRaftServerProtocol(
      final MemberId memberId,
      final Map<MemberId, ControllableRaftServerProtocol> servers,
      final Map<MemberId, Queue<Tuple<Runnable, CompletableFuture<?>>>> messageQueue) {
    this.servers = servers;
    this.messageQueue = messageQueue;
    localMemberId = memberId;
    messageQueue.put(memberId, new LinkedList<>());
    servers.put(memberId, this);
  }

  public void receiveNextMessage() {
    final var rcvQueue = messageQueue.get(localMemberId);
    if (!rcvQueue.isEmpty()) {
      final Tuple<Runnable, CompletableFuture<?>> message = rcvQueue.poll();
      message.getLeft().run();
      timeoutQueue.remove(message.getRight());
    }
  }

  public void receiveAll() {
    final var rcvQueue = messageQueue.get(localMemberId);
    while (!rcvQueue.isEmpty()) {
      final Tuple<Runnable, CompletableFuture<?>> message = rcvQueue.poll();
      message.getLeft().run();
      timeoutQueue.remove(message.getRight());
    }
  }

  // drop next message from the incoming queue
  public void dropNextMessage() {
    final var nextMessage =
        messageQueue.computeIfAbsent(localMemberId, t -> new LinkedList<>()).poll();
    if (nextMessage != null) {
      Optional.ofNullable(nextMessage.getRight())
          .ifPresent(
              f -> {
                LOG.info("Dropped a message to {}", localMemberId.id());
                // RaftServers excepts exceptions from the messaging layer to detect timeouts
                final var e = new TimeoutException();
                // Hide the stack trace to avoid polluting the logs
                e.setStackTrace(new StackTraceElement[0]);
                f.completeExceptionally(e);
              });
    }
  }

  ControllableRaftServerProtocol server(final MemberId memberId) {
    return servers.get(memberId);
  }

  // Add a message to the outgoing queue
  private void send(
      final MemberId memberId,
      final Runnable requestHandler,
      final CompletableFuture<?> responseFuture) {
    final var message = new Tuple<Runnable, CompletableFuture<?>>(requestHandler, responseFuture);
    messageQueue.computeIfAbsent(memberId, m -> new LinkedList<>()).add(message);
    addTimeOut(responseFuture);
  }

  private void addTimeOut(final CompletableFuture<?> responseFuture) {
    if (responseFuture != null) {
      timeoutQueue.put(responseFuture, currentTime + requestTimeoutMillis);
    }
  }

  public void tick(final long timeoutMillis) {
    currentTime += timeoutMillis;
    // timeout messages
    final var iter = timeoutQueue.entrySet().iterator();
    while (iter.hasNext()) {
      final var entry = iter.next();
      final Long deadline = entry.getValue();
      if (currentTime >= deadline) {
        final CompletableFuture<?> messageFuture = entry.getKey();
        if (!messageFuture.isDone()) {
          final var timeout = new TimeoutException();
          // Hide the stack trace to avoid polluting the logs
          timeout.setStackTrace(new StackTraceElement[0]);
          messageFuture.completeExceptionally(timeout);
        }
        iter.remove();
      }
    }
  }

  @Override
  public CompletableFuture<ConfigureResponse> configure(
      final MemberId memberId, final ConfigureRequest request) {
    final var responseFuture = new CompletableFuture<ConfigureResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.configure(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<ReconfigureResponse> reconfigure(
      final MemberId memberId, final ReconfigureRequest request) {
    final var responseFuture = new CompletableFuture<ReconfigureResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.reconfigure(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<ForceConfigureResponse> forceConfigure(
      final MemberId memberId, final ForceConfigureRequest request) {
    final var responseFuture = new CompletableFuture<ForceConfigureResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.forceConfigure(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<JoinResponse> join(final MemberId memberId, final JoinRequest request) {
    final var responseFuture = new CompletableFuture<JoinResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.join(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(
      final MemberId memberId, final LeaveRequest request) {
    final var responseFuture = new CompletableFuture<LeaveResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.leave(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<InstallResponse> install(
      final MemberId memberId, final InstallRequest request) {
    final var responseFuture = new CompletableFuture<InstallResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.install(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<TransferResponse> transfer(
      final MemberId memberId, final TransferRequest request) {
    final var responseFuture = new CompletableFuture<TransferResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.transfer(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<PollResponse> poll(final MemberId memberId, final PollRequest request) {
    final var responseFuture = new CompletableFuture<PollResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.poll(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final MemberId memberId, final VoteRequest request) {
    final var responseFuture = new CompletableFuture<VoteResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.vote(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public CompletableFuture<AppendResponse> append(
      final MemberId memberId, final AppendRequest request) {
    throw new UnsupportedOperationException("Cannot use old version in tests");
  }

  @Override
  public CompletableFuture<AppendResponse> append(
      final MemberId memberId, final VersionedAppendRequest request) {
    final var responseFuture = new CompletableFuture<AppendResponse>();
    send(
        memberId,
        () ->
            getServer(memberId)
                .thenCompose(listener -> listener.append(request))
                .thenAccept(
                    response -> send(localMemberId, () -> responseFuture.complete(response), null)),
        responseFuture);
    return responseFuture;
  }

  @Override
  public void registerTransferHandler(
      final Function<TransferRequest, CompletableFuture<TransferResponse>> handler) {
    transferHandler = handler;
  }

  @Override
  public void unregisterTransferHandler() {
    transferHandler = null;
  }

  @Override
  public void registerConfigureHandler(
      final Function<ConfigureRequest, CompletableFuture<ConfigureResponse>> handler) {
    configureHandler = handler;
  }

  @Override
  public void unregisterConfigureHandler() {
    configureHandler = null;
  }

  @Override
  public void registerReconfigureHandler(
      final Function<ReconfigureRequest, CompletableFuture<ReconfigureResponse>> handler) {
    reconfigureHandler = handler;
  }

  @Override
  public void unregisterReconfigureHandler() {
    reconfigureHandler = null;
  }

  @Override
  public void registerForceConfigureHandler(
      final Function<ForceConfigureRequest, CompletableFuture<ForceConfigureResponse>> handler) {}

  @Override
  public void unregisterForceConfigureHandler() {}

  @Override
  public void registerJoinHandler(
      final Function<JoinRequest, CompletableFuture<JoinResponse>> handler) {
    joinHandler = handler;
  }

  @Override
  public void unregisterJoinHandler() {
    joinHandler = null;
  }

  @Override
  public void registerLeaveHandler(
      final Function<LeaveRequest, CompletableFuture<LeaveResponse>> handler) {
    leaveHandler = handler;
  }

  @Override
  public void unregisterLeaveHandler() {
    leaveHandler = null;
  }

  @Override
  public void registerInstallHandler(
      final Function<InstallRequest, CompletableFuture<InstallResponse>> handler) {
    installHandler = handler;
  }

  @Override
  public void unregisterInstallHandler() {
    installHandler = null;
  }

  @Override
  public void registerPollHandler(
      final Function<PollRequest, CompletableFuture<PollResponse>> handler) {
    pollHandler = handler;
  }

  @Override
  public void unregisterPollHandler() {
    pollHandler = null;
  }

  @Override
  public void registerVoteHandler(
      final Function<VoteRequest, CompletableFuture<VoteResponse>> handler) {
    voteHandler = handler;
  }

  @Override
  public void unregisterVoteHandler() {
    voteHandler = null;
  }

  @Override
  public void registerAppendV1Handler(
      final Function<AppendRequest, CompletableFuture<AppendResponse>> handler) {}

  @Override
  public void registerAppendV2Handler(
      final Function<VersionedAppendRequest, CompletableFuture<AppendResponse>> handler) {
    appendHandler = handler;
  }

  @Override
  public void unregisterAppendHandler() {
    appendHandler = null;
  }

  private CompletableFuture<ControllableRaftServerProtocol> getServer(final MemberId memberId) {
    final ControllableRaftServerProtocol server = server(memberId);
    if (server != null) {
      return CompletableFuture.completedFuture(server);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<AppendResponse> append(final VersionedAppendRequest request) {
    if (appendHandler != null) {
      return appendHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (voteHandler != null) {
      return voteHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<PollResponse> poll(final PollRequest request) {
    if (pollHandler != null) {
      return pollHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<TransferResponse> transfer(final TransferRequest request) {
    if (transferHandler != null) {
      return transferHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<InstallResponse> install(final InstallRequest request) {
    if (installHandler != null) {
      return installHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<ReconfigureResponse> reconfigure(final ReconfigureRequest request) {
    if (reconfigureHandler != null) {
      return reconfigureHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<ForceConfigureResponse> forceConfigure(final ForceConfigureRequest request) {
    if (forceConfigureHandler != null) {
      return forceConfigureHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<JoinResponse> join(final JoinRequest request) {
    if (joinHandler != null) {
      return joinHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    if (leaveHandler != null) {
      return leaveHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }

  CompletableFuture<ConfigureResponse> configure(final ConfigureRequest request) {
    if (configureHandler != null) {
      return configureHandler.apply(request);
    } else {
      return CompletableFuture.failedFuture(new ConnectException());
    }
  }
}
