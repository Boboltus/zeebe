/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.processinstance;

import io.camunda.zeebe.auth.impl.TenantAuthorizationCheckerImpl;
import io.camunda.zeebe.engine.processing.deployment.model.element.AbstractFlowElement;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableActivity;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableBoundaryEvent;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableUserTask;
import io.camunda.zeebe.engine.state.deployment.DeployedProcess;
import io.camunda.zeebe.engine.state.immutable.ElementInstanceState;
import io.camunda.zeebe.engine.state.immutable.EventScopeInstanceState;
import io.camunda.zeebe.engine.state.instance.ElementInstance;
import io.camunda.zeebe.engine.state.instance.EventTrigger;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.BpmnEventType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceMigrationRecordValue.ProcessInstanceMigrationMappingInstructionValue;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;

public final class ProcessInstanceMigrationPreconditionChecker {

  private static final EnumSet<BpmnElementType> SUPPORTED_ELEMENT_TYPES =
      EnumSet.of(BpmnElementType.PROCESS, BpmnElementType.SERVICE_TASK, BpmnElementType.USER_TASK);
  private static final Set<BpmnElementType> UNSUPPORTED_ELEMENT_TYPES =
      EnumSet.complementOf(SUPPORTED_ELEMENT_TYPES);

  private static final String ERROR_MESSAGE_PROCESS_INSTANCE_NOT_FOUND =
      "Expected to migrate process instance but no process instance found with key '%d'";
  private static final String ERROR_MESSAGE_PROCESS_DEFINITION_NOT_FOUND =
      "Expected to migrate process instance to process definition but no process definition found with key '%d'";
  private static final String ERROR_MESSAGE_DUPLICATE_SOURCE_ELEMENT_IDS =
      "Expected to migrate process instance '%s' but the mapping instructions contain duplicate source element ids '%s'.";
  private static final String ERROR_CHILD_PROCESS_INSTANCE =
      "Expected to migrate process instance '%s' but process instance is a child process instance. Child process instances cannot be migrated.";
  private static final String ERROR_SOURCE_ELEMENT_ID_NOT_FOUND =
      """
              Expected to migrate process instance '%s' \
              but mapping instructions contain a non-existing source element id '%s'. \
              Elements provided in mapping instructions must exist \
              in the source process definition.""";
  private static final String ERROR_TARGET_ELEMENT_ID_NOT_FOUND =
      """
              Expected to migrate process instance '%s' \
              but mapping instructions contain a non-existing target element id '%s'. \
              Elements provided in mapping instructions must exist \
              in the target process definition.""";
  private static final String ERROR_MESSAGE_EVENT_SUBPROCESS_NOT_SUPPORTED_IN_PROCESS_INSTANCE =
      "Expected to migrate process instance but process instance has an event subprocess. Process instances with event subprocesses cannot be migrated yet.";
  private static final String ERROR_MESSAGE_EVENT_SUBPROCESS_NOT_SUPPORTED_IN_TARGET_PROCESS =
      "Expected to migrate process instance but target process has an event subprocess. Target processes with event subprocesses cannot be migrated yet.";
  private static final String ERROR_UNSUPPORTED_ELEMENT_TYPE =
      "Expected to migrate process instance '%s' but active element with id '%s' has an unsupported type. The migration of a %s is not supported.";
  private static final String ERROR_UNMAPPED_ACTIVE_ELEMENT =
      "Expected to migrate process instance '%s' but no mapping instruction defined for active element with id '%s'. Elements cannot be migrated without a mapping.";
  private static final String ERROR_ELEMENT_TYPE_CHANGED =
      """
              Expected to migrate process instance '%s' \
              but active element with id '%s' and type '%s' is mapped to \
              an element with id '%s' and different type '%s'. \
              Elements must be mapped to elements of the same type.""";

  private static final String ERROR_USER_TASK_IMPLEMENTATION_CHANGED =
      """
              Expected to migrate process instance '%s' \
              but active user task with id '%s' and implementation '%s' is mapped to \
              an user task with id '%s' and different implementation '%s'. \
              Elements must be mapped to elements of the same implementation.""";
  private static final String ERROR_MESSAGE_ELEMENT_FLOW_SCOPE_CHANGED =
      """
              Expected to migrate process instance '%s' \
              but the flow scope of active element with id '%s' is changed. \
              The flow scope of the active element is expected to be '%s' but was '%s'. \
              The flow scope of an element cannot be changed during migration yet.""";
  private static final String ERROR_ACTIVE_ELEMENT_WITH_BOUNDARY_EVENT =
      """
              Expected to migrate process instance '%s' \
              but active element with id '%s' has one or more boundary events of types '%s'. \
              Migrating active elements with boundary events of these types is not possible yet.""";
  private static final String ERROR_TARGET_ELEMENT_WITH_BOUNDARY_EVENT =
      """
              Expected to migrate process instance '%s' \
              but target element with id '%s' has a boundary event. \
              Migrating target elements with boundary events is not possible yet.""";
  private static final String ERROR_CONCURRENT_COMMAND =
      "Expected to migrate process instance '%s' but a concurrent command was executed on the process instance. Please retry the migration.";
  private static final long NO_PARENT = -1L;
  private static final String ZEEBE_USER_TASK_IMPLEMENTATION = "zeebe user task";
  private static final String JOB_WORKER_IMPLEMENTATION = "job worker";

  /**
   * Checks whether the given record exists. Throws exception if given process instance record is
   * null.
   *
   * @param record process instance record to do the null check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireNonNullProcessInstance(
      final ElementInstance record, final long processInstanceKey) {
    if (record == null) {
      final String reason =
          String.format(ERROR_MESSAGE_PROCESS_INSTANCE_NOT_FOUND, processInstanceKey);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.NOT_FOUND);
    }
  }

  /**
   * Checks whether given tenant is authorized for the process given instance.
   *
   * @param authorizations list of authorizations available
   * @param tenantId tenant id to be checked
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireAuthorizedTenant(
      final Map<String, Object> authorizations,
      final String tenantId,
      final long processInstanceKey) {
    final boolean isTenantAuthorized =
        TenantAuthorizationCheckerImpl.fromAuthorizationMap(authorizations).isAuthorized(tenantId);
    if (!isTenantAuthorized) {
      final String reason =
          String.format(ERROR_MESSAGE_PROCESS_INSTANCE_NOT_FOUND, processInstanceKey);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.NOT_FOUND);
    }
  }

  /**
   * Checks whether the given process instance key is a parent key. Throws exception if given parent
   * process key is a parent.
   *
   * @param parentProcessInstanceKey parent process instance key to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireNullParent(
      final long parentProcessInstanceKey, final long processInstanceKey) {
    if (parentProcessInstanceKey != NO_PARENT) {
      final String reason = String.format(ERROR_CHILD_PROCESS_INSTANCE, processInstanceKey);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given target process definition exists. Throws exception if given target
   * process definition is null.
   *
   * @param targetProcessDefinition target process definition to do the null check
   * @param targetProcessDefinitionKey target process definition key to be logged
   */
  public static void requireNonNullTargetProcessDefinition(
      final DeployedProcess targetProcessDefinition, final long targetProcessDefinitionKey) {
    if (targetProcessDefinition == null) {
      final String reason =
          String.format(ERROR_MESSAGE_PROCESS_DEFINITION_NOT_FOUND, targetProcessDefinitionKey);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.NOT_FOUND);
    }
  }

  /**
   * Checks whether the given mapping instructions contain duplicate source element ids. Throws an
   * exception if duplicate source element ids are found.
   *
   * @param mappingInstructions mapping instructions to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireNonDuplicateSourceElementIds(
      final List<ProcessInstanceMigrationMappingInstructionValue> mappingInstructions,
      final long processInstanceKey) {
    final Map<String, Long> countBySourceElementId =
        mappingInstructions.stream()
            .collect(
                Collectors.groupingBy(
                    ProcessInstanceMigrationMappingInstructionValue::getSourceElementId,
                    Collectors.counting()));
    final List<String> duplicateSourceElementIds =
        countBySourceElementId.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Entry::getKey)
            .toList();

    if (!duplicateSourceElementIds.isEmpty()) {
      final String reason =
          String.format(
              ERROR_MESSAGE_DUPLICATE_SOURCE_ELEMENT_IDS,
              processInstanceKey,
              duplicateSourceElementIds);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_ARGUMENT);
    }
  }

  /**
   * Checks whether the given mapping instructions refer to existing elements in the source and the
   * target process definition. Throws an exception if any of the mapping instructions refers to a
   * non-existing element.
   *
   * @param sourceProcessDefinition source process definition
   * @param targetProcessDefinition target process definition
   * @param mappingInstructions mapping instructions to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireReferredElementsExist(
      final DeployedProcess sourceProcessDefinition,
      final DeployedProcess targetProcessDefinition,
      final List<ProcessInstanceMigrationMappingInstructionValue> mappingInstructions,
      final long processInstanceKey) {

    mappingInstructions.forEach(
        instruction -> {
          final String sourceElementId = instruction.getSourceElementId();
          if (sourceProcessDefinition.getProcess().getElementById(sourceElementId) == null) {
            final String reason =
                String.format(
                    ERROR_SOURCE_ELEMENT_ID_NOT_FOUND, processInstanceKey, sourceElementId);
            throw new ProcessInstanceMigrationPreconditionFailedException(
                reason, RejectionType.INVALID_ARGUMENT);
          }

          final String targetElementId = instruction.getTargetElementId();
          if (targetProcessDefinition.getProcess().getElementById(targetElementId) == null) {
            final String reason =
                String.format(
                    ERROR_TARGET_ELEMENT_ID_NOT_FOUND, processInstanceKey, targetElementId);
            throw new ProcessInstanceMigrationPreconditionFailedException(
                reason, RejectionType.INVALID_ARGUMENT);
          }
        });
  }

  /**
   * Checks whether the given source and target process definition contain event subprocesses.
   *
   * @param sourceProcessDefinition source process definition
   * @param targetProcessDefinition target process definition
   */
  public static void requireNoEventSubprocess(
      final DeployedProcess sourceProcessDefinition,
      final DeployedProcess targetProcessDefinition) {
    if (!sourceProcessDefinition.getProcess().getEventSubprocesses().isEmpty()) {
      throw new ProcessInstanceMigrationPreconditionFailedException(
          ERROR_MESSAGE_EVENT_SUBPROCESS_NOT_SUPPORTED_IN_PROCESS_INSTANCE,
          RejectionType.INVALID_STATE);
    }

    if (!targetProcessDefinition.getProcess().getEventSubprocesses().isEmpty()) {
      throw new ProcessInstanceMigrationPreconditionFailedException(
          ERROR_MESSAGE_EVENT_SUBPROCESS_NOT_SUPPORTED_IN_TARGET_PROCESS,
          RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given element instance is of a supported type. Throws an exception if the
   * element instance is of an unsupported type.
   *
   * @param elementInstanceRecord element instance to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireSupportedElementType(
      final ProcessInstanceRecord elementInstanceRecord, final long processInstanceKey) {
    if (UNSUPPORTED_ELEMENT_TYPES.contains(elementInstanceRecord.getBpmnElementType())) {
      final String reason =
          String.format(
              ERROR_UNSUPPORTED_ELEMENT_TYPE,
              processInstanceKey,
              elementInstanceRecord.getElementId(),
              elementInstanceRecord.getBpmnElementType());
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given target element id exists. Throws an exception if the target element id
   * is null.
   *
   * @param targetElementId target element id to do the null check
   * @param processInstanceKey process instance key to be logged
   * @param sourceElementId source element id to be logged
   */
  public static void requireNonNullTargetElementId(
      final String targetElementId, final long processInstanceKey, final String sourceElementId) {
    if (targetElementId == null) {
      final String reason =
          String.format(ERROR_UNMAPPED_ACTIVE_ELEMENT, processInstanceKey, sourceElementId);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given element instance has the same element type as the target element.
   * Throws an exception if the element instance has a different type.
   *
   * @param targetProcessDefinition target process definition to retrieve the target element type
   * @param targetElementId target element id
   * @param elementInstanceRecord element instance to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireSameElementType(
      final DeployedProcess targetProcessDefinition,
      final String targetElementId,
      final ProcessInstanceRecord elementInstanceRecord,
      final long processInstanceKey) {
    final BpmnElementType targetElementType =
        targetProcessDefinition.getProcess().getElementById(targetElementId).getElementType();
    if (elementInstanceRecord.getBpmnElementType() != targetElementType) {
      final String reason =
          String.format(
              ERROR_ELEMENT_TYPE_CHANGED,
              processInstanceKey,
              elementInstanceRecord.getElementId(),
              elementInstanceRecord.getBpmnElementType(),
              targetElementId,
              targetElementType);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Since we introduce zeebe user tasks and job worker tasks has the same bpmn element type, we
   * need to check whether the given element instance and target element has the same user task
   * type. Throws an exception if they have different types.
   *
   * @param targetProcessDefinition target process definition to retrieve the target element type
   * @param targetElementId target element id
   * @param elementInstance element instance to do the check
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireSameUserTaskImplementation(
      final DeployedProcess targetProcessDefinition,
      final String targetElementId,
      final ElementInstance elementInstance,
      final long processInstanceKey) {
    final ProcessInstanceRecord elementInstanceRecord = elementInstance.getValue();
    if (elementInstanceRecord.getBpmnElementType() != BpmnElementType.USER_TASK) {
      return;
    }

    final AbstractFlowElement targetElement =
        targetProcessDefinition.getProcess().getElementById(targetElementId);
    final BpmnElementType targetElementType = targetElement.getElementType();
    if (targetElementType != BpmnElementType.USER_TASK) {
      return;
    }

    final ExecutableUserTask targetUserTask =
        targetProcessDefinition
            .getProcess()
            .getElementById(targetElementId, ExecutableUserTask.class);
    final String targetUserTaskType =
        targetUserTask.getUserTaskProperties() != null
            ? ZEEBE_USER_TASK_IMPLEMENTATION
            : JOB_WORKER_IMPLEMENTATION;
    final String sourceUserTaskType =
        elementInstance.getUserTaskKey() > 0
            ? ZEEBE_USER_TASK_IMPLEMENTATION
            : JOB_WORKER_IMPLEMENTATION;

    if (!targetUserTaskType.equals(sourceUserTaskType)) {
      final String reason =
          String.format(
              ERROR_USER_TASK_IMPLEMENTATION_CHANGED,
              processInstanceKey,
              elementInstanceRecord.getElementId(),
              sourceUserTaskType,
              targetElementId,
              targetUserTaskType);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given element instance has the same flow scope id as the target element.
   * Throws an exception if the element instance has a different flow scope.
   *
   * @param elementInstanceState element instance state to retrieve the source flow scope element
   * @param elementInstanceRecord element instance to do the check
   * @param targetProcessDefinition target process definition to retrieve the target element
   * @param targetElementId target element id to retrieve the target flow scope
   */
  public static void requireUnchangedFlowScope(
      final ElementInstanceState elementInstanceState,
      final ProcessInstanceRecord elementInstanceRecord,
      final DeployedProcess targetProcessDefinition,
      final String targetElementId) {
    final ElementInstance sourceFlowScopeElement =
        elementInstanceState.getInstance(elementInstanceRecord.getFlowScopeKey());
    if (sourceFlowScopeElement != null) {
      final DirectBuffer expectedFlowScopeId =
          sourceFlowScopeElement.getValue().getElementIdBuffer();
      final DirectBuffer actualFlowScopeId =
          targetProcessDefinition
              .getProcess()
              .getElementById(targetElementId)
              .getFlowScope()
              .getId();

      if (!expectedFlowScopeId.equals(actualFlowScopeId)) {
        final String reason =
            String.format(
                ERROR_MESSAGE_ELEMENT_FLOW_SCOPE_CHANGED,
                elementInstanceRecord.getProcessInstanceKey(),
                elementInstanceRecord.getElementId(),
                BufferUtil.bufferAsString(expectedFlowScopeId),
                BufferUtil.bufferAsString(actualFlowScopeId));
        throw new ProcessInstanceMigrationPreconditionFailedException(
            reason, RejectionType.INVALID_STATE);
      }
    }
  }

  /**
   * Checks whether the given source process definition contains a boundary event. Throws an
   * exception if the source process definition contains a boundary event that is not allowed.
   *
   * @param sourceProcessDefinition source process definition to do the check
   * @param elementInstanceRecord element instance to be logged
   * @param allowedEventTypes allowed event types for the boundary event
   */
  public static void requireNoBoundaryEventInSource(
      final DeployedProcess sourceProcessDefinition,
      final ProcessInstanceRecord elementInstanceRecord,
      final EnumSet<BpmnEventType> allowedEventTypes) {
    final List<ExecutableBoundaryEvent> boundaryEvents =
        sourceProcessDefinition
            .getProcess()
            .getElementById(elementInstanceRecord.getElementId(), ExecutableActivity.class)
            .getBoundaryEvents();

    final var rejectedBoundaryEvents =
        boundaryEvents.stream()
            .filter(event -> !allowedEventTypes.contains(event.getEventType()))
            .toList();

    if (!rejectedBoundaryEvents.isEmpty()) {
      final String rejectedEventTypes =
          rejectedBoundaryEvents.stream()
              .map(ExecutableBoundaryEvent::getEventType)
              .map(BpmnEventType::name)
              .collect(Collectors.joining(","));
      final String reason =
          String.format(
              ERROR_ACTIVE_ELEMENT_WITH_BOUNDARY_EVENT,
              elementInstanceRecord.getProcessInstanceKey(),
              elementInstanceRecord.getElementId(),
              rejectedEventTypes);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given target process definition contains a boundary event. Throws an
   * exception if the target process definition contains a boundary event.
   *
   * @param targetProcessDefinition target process definition to do the check
   * @param targetElementId target element id to retrieve the target element
   * @param elementInstanceRecord element instance to be logged
   */
  public static void requireNoBoundaryEventInTarget(
      final DeployedProcess targetProcessDefinition,
      final String targetElementId,
      final ProcessInstanceRecord elementInstanceRecord) {
    final boolean hasBoundaryEventInTarget =
        !targetProcessDefinition
            .getProcess()
            .getElementById(targetElementId, ExecutableActivity.class)
            .getBoundaryEvents()
            .isEmpty();

    if (hasBoundaryEventInTarget) {
      final String reason =
          String.format(
              ERROR_TARGET_ELEMENT_WITH_BOUNDARY_EVENT,
              elementInstanceRecord.getProcessInstanceKey(),
              elementInstanceRecord.getElementId());
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  /**
   * Checks whether the given process instance has a concurrent command. Throws an exception if the
   * given process instance has a concurrent command.
   *
   * <p>Some concurrent commands are a job complete, a timer trigger, or a message correlation.
   * Since the concurrent command modifies the process instance, it is not safe to apply the
   * migration in between.
   *
   * @param eventScopeInstanceState event scope instance state to retrieve the event trigger
   * @param elementInstance element instance to do the check active sequence flows
   * @param processInstanceKey process instance key to be logged
   */
  public static void requireNoConcurrentCommand(
      final EventScopeInstanceState eventScopeInstanceState,
      final ElementInstance elementInstance,
      final long processInstanceKey) {
    final EventTrigger eventTrigger =
        eventScopeInstanceState.peekEventTrigger(elementInstance.getKey());

    // An event trigger indicates a concurrent command. It is created when completing a job, or
    // triggering a timer/message/signal event.
    // or
    // An active sequence flow indicates a concurrent command. It is created when taking a
    // sequence flow and writing an ACTIVATE command for the next element.
    if (eventTrigger != null || elementInstance.getActiveSequenceFlows() > 0) {
      final String reason = String.format(ERROR_CONCURRENT_COMMAND, processInstanceKey);
      throw new ProcessInstanceMigrationPreconditionFailedException(
          reason, RejectionType.INVALID_STATE);
    }
  }

  public static final class ProcessInstanceMigrationPreconditionFailedException
      extends RuntimeException {
    private final RejectionType rejectionType;

    public ProcessInstanceMigrationPreconditionFailedException(
        final String message, final RejectionType rejectionType) {
      super(message);
      this.rejectionType = rejectionType;
    }

    public RejectionType getRejectionType() {
      return rejectionType;
    }
  }
}
