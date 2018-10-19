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
package io.zeebe.zeebemonitor.rest;

import io.zeebe.zeebemonitor.entity.IncidentEntity;
import io.zeebe.zeebemonitor.entity.WorkflowEntity;
import io.zeebe.zeebemonitor.entity.WorkflowInstanceEntity;
import io.zeebe.zeebemonitor.repository.IncidentRepository;
import io.zeebe.zeebemonitor.repository.WorkflowInstanceRepository;
import io.zeebe.zeebemonitor.repository.WorkflowRepository;
import io.zeebe.zeebemonitor.zeebe.ZeebeConnectionService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instances")
public class WorkflowInstanceResource {

  @Autowired private ZeebeConnectionService connections;

  @Autowired private WorkflowInstanceRepository workflowInstanceRepository;

  @Autowired private WorkflowRepository workflowRepository;

  @Autowired private IncidentRepository incidentRepository;

  @RequestMapping("/")
  public Iterable<WorkflowInstanceDto> getWorkflowInstances() {

    final Map<Long, WorkflowEntity> workflows =
        StreamSupport.stream(workflowRepository.findAll().spliterator(), false)
            .collect(Collectors.toMap(WorkflowEntity::getKey, Function.identity()));

    final Map<Long, List<IncidentEntity>> incidentsByWorkflowInstanceKey =
        StreamSupport.stream(incidentRepository.findAll().spliterator(), false)
            .collect(Collectors.groupingBy(IncidentEntity::getWorkflowInstanceKey));

    final Map<Long, List<WorkflowInstanceEntity>> entitiesByWorkflowInstanceKey =
        StreamSupport.stream(workflowInstanceRepository.findAll().spliterator(), false)
            .collect(Collectors.groupingBy(WorkflowInstanceEntity::getWorkflowInstanceKey));

    final List<WorkflowInstanceDto> dtos =
        entitiesByWorkflowInstanceKey
            .entrySet()
            .stream()
            .map(
                entry -> {
                  final Long workflowInstanceKey = entry.getKey();

                  final List<WorkflowInstanceEntity> events = entry.getValue();
                  final WorkflowInstanceEntity lastEvent = events.get(events.size() - 1);

                  final WorkflowInstanceDto dto = new WorkflowInstanceDto();
                  dto.setWorkflowInstanceKey(workflowInstanceKey);

                  dto.setPartitionId(lastEvent.getPartitionId());

                  dto.setWorkflowKey(lastEvent.getWorkflowKey());
                  dto.setPayload(lastEvent.getPayload());

                  final WorkflowEntity workflow = workflows.get(lastEvent.getWorkflowKey());
                  if (workflow != null) {
                    dto.setBpmnProcessId(workflow.getBpmnProcessId());
                    dto.setWorkflowVersion(workflow.getVersion());
                  }

                  final boolean isEnded =
                      events
                          .stream()
                          .anyMatch(
                              e ->
                                  e.getKey() == workflowInstanceKey
                                      && (e.getIntent().equals("ELEMENT_COMPLETED")
                                          || e.getIntent().equals("ELEMENT_TERMINATED")));
                  dto.setEnded(isEnded);

                  final List<String> completedActivities =
                      events
                          .stream()
                          .filter(
                              e ->
                                  (e.getIntent().equals("ELEMENT_COMPLETED")
                                          || e.getIntent().equals("ELEMENT_TERMINATED"))
                                      && e.getKey() != workflowInstanceKey)
                          .map(WorkflowInstanceEntity::getActivityId)
                          .collect(Collectors.toList());
                  dto.setEndedActivities(completedActivities);

                  final List<String> activeAcitivities =
                      events
                          .stream()
                          .filter(
                              e ->
                                  e.getIntent().equals("ELEMENT_ACTIVATED")
                                      && e.getKey() != workflowInstanceKey)
                          .map(WorkflowInstanceEntity::getActivityId)
                          .filter(id -> !completedActivities.contains(id))
                          .collect(Collectors.toList());
                  dto.setRunningActivities(activeAcitivities);

                  final List<String> takenSequenceFlows =
                      events
                          .stream()
                          .filter(e -> e.getIntent().equals("SEQUENCE_FLOW_TAKEN"))
                          .map(WorkflowInstanceEntity::getActivityId)
                          .collect(Collectors.toList());
                  dto.setTakenSequenceFlows(takenSequenceFlows);

                  final List<IncidentEntity> incidents =
                      incidentsByWorkflowInstanceKey.get(workflowInstanceKey);
                  if (incidents != null) {
                    incidents
                        .stream()
                        .collect(Collectors.groupingBy(IncidentEntity::getIncidentKey))
                        .entrySet()
                        .stream()
                        .map(
                            i -> {
                              final Long incidentKey = i.getKey();

                              final List<IncidentEntity> incidentEvents = i.getValue();
                              final IncidentEntity lastIncidentEvent =
                                  incidentEvents.get(incidentEvents.size() - 1);

                              final IncidentDto incidentDto = new IncidentDto();
                              incidentDto.setKey(incidentKey);
                              incidentDto.setActivityInstanceKey(
                                  lastIncidentEvent.getActivityInstanceKey());
                              incidentDto.setJobKey(lastIncidentEvent.getJobKey());
                              incidentDto.setErrorType(lastIncidentEvent.getErrorType());
                              incidentDto.setErrorMessage(lastIncidentEvent.getErrorMessage());

                              final boolean isResolved =
                                  lastIncidentEvent.getIntent().equals("RESOLVED")
                                      || lastIncidentEvent.getIntent().equals("DELETED");
                              incidentDto.setResolved(isResolved);

                              return incidentDto;
                            });
                  }

                  return dto;
                })
            .collect(Collectors.toList());

    return dtos;
  }

  @RequestMapping("/{key}")
  public WorkflowInstanceDto getWorkflowInstance(@PathVariable("key") long key) {

    final List<WorkflowInstanceEntity> events =
        StreamSupport.stream(
                workflowInstanceRepository.findByWorkflowInstanceKey(key).spliterator(), false)
            .collect(Collectors.toList());

    final WorkflowInstanceEntity lastEvent = events.get(events.size() - 1);

    final WorkflowInstanceDto dto = new WorkflowInstanceDto();
    dto.setWorkflowInstanceKey(key);

    dto.setPartitionId(lastEvent.getPartitionId());

    dto.setWorkflowKey(lastEvent.getWorkflowKey());
    dto.setPayload(lastEvent.getPayload());

    workflowRepository
        .findByKey(lastEvent.getWorkflowKey())
        .ifPresent(
            workflow -> {
              dto.setBpmnProcessId(workflow.getBpmnProcessId());
              dto.setWorkflowVersion(workflow.getVersion());
            });

    final boolean isEnded =
        events
            .stream()
            .anyMatch(e -> e.getKey() == key && e.getIntent().equals("ELEMENT_COMPLETED"));
    dto.setEnded(isEnded);

    final List<String> completedActivities =
        events
            .stream()
            .filter(e -> e.getIntent().equals("ELEMENT_COMPLETED") && e.getKey() != key)
            .map(WorkflowInstanceEntity::getActivityId)
            .collect(Collectors.toList());
    dto.setEndedActivities(completedActivities);

    final List<String> activeAcitivities =
        events
            .stream()
            .filter(e -> e.getIntent().equals("ELEMENT_ACTIVATED") && e.getKey() != key)
            .map(WorkflowInstanceEntity::getActivityId)
            .filter(id -> !completedActivities.contains(id))
            .collect(Collectors.toList());
    dto.setRunningActivities(activeAcitivities);

    final List<String> takenSequenceFlows =
        events
            .stream()
            .filter(e -> e.getIntent().equals("SEQUENCE_FLOW_TAKEN"))
            .map(WorkflowInstanceEntity::getActivityId)
            .collect(Collectors.toList());
    dto.setTakenSequenceFlows(takenSequenceFlows);

    final List<IncidentEntity> incidents =
        StreamSupport.stream(incidentRepository.findByWorkflowInstanceKey(key).spliterator(), false)
            .collect(Collectors.toList());

    incidents
        .stream()
        .collect(Collectors.groupingBy(IncidentEntity::getIncidentKey))
        .entrySet()
        .stream()
        .forEach(
            i -> {
              final Long incidentKey = i.getKey();

              final List<IncidentEntity> incidentEvents = i.getValue();
              final IncidentEntity lastIncidentEvent =
                  incidentEvents.get(incidentEvents.size() - 1);

              final IncidentDto incidentDto = new IncidentDto();
              incidentDto.setKey(incidentKey);
              incidentDto.setActivityInstanceKey(lastIncidentEvent.getActivityInstanceKey());
              incidentDto.setJobKey(lastIncidentEvent.getJobKey());
              incidentDto.setErrorType(lastIncidentEvent.getErrorType());
              incidentDto.setErrorMessage(lastIncidentEvent.getErrorMessage());

              final boolean isResolved =
                  lastIncidentEvent.getIntent().equals("RESOLVED")
                      || lastIncidentEvent.getIntent().equals("DELETED");
              incidentDto.setResolved(isResolved);

              if (!isResolved) {
                dto.getIncidents().add(incidentDto);
              }
            });

    return dto;
  }

  @RequestMapping(path = "/{key}", method = RequestMethod.DELETE)
  public void cancelWorkflowInstance(@PathVariable("key") long key) throws Exception {
    connections.getClient().workflowClient().newCancelInstanceCommand(key).send().join();
  }

  @RequestMapping(path = "/{key}/update-payload", method = RequestMethod.PUT)
  public void updatePayload(@PathVariable("key") long key, @RequestBody String payload)
      throws Exception {
    connections
        .getClient()
        .workflowClient()
        .newUpdatePayloadCommand(key)
        .payload(payload)
        .send()
        .join();
  }

  @RequestMapping(path = "/{key}/update-retries", method = RequestMethod.PUT)
  public void updateRetries(@PathVariable("key") long key) throws Exception {
    connections.getClient().jobClient().newUpdateRetriesCommand(key).retries(2).send().join();
  }
}