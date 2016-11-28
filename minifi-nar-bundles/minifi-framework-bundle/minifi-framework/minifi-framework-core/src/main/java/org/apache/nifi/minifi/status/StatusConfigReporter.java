/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.minifi.status;

import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ReportingTaskNode;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.controller.status.RemoteProcessGroupStatus;
import org.apache.nifi.minifi.commons.status.FlowStatusReport;
import org.apache.nifi.minifi.commons.status.connection.ConnectionStatusBean;
import org.apache.nifi.minifi.commons.status.controllerservice.ControllerServiceStatus;
import org.apache.nifi.minifi.commons.status.instance.InstanceStatus;
import org.apache.nifi.minifi.commons.status.processor.ProcessorStatusBean;
import org.apache.nifi.minifi.commons.status.reportingTask.ReportingTaskStatus;
import org.apache.nifi.minifi.commons.status.rpg.RemoteProcessGroupStatusBean;
import org.apache.nifi.minifi.commons.status.system.SystemDiagnosticsStatus;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.nifi.minifi.status.StatusRequestParser.parseConnectionStatusRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseControllerServiceStatusRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseInstanceRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseProcessorStatusRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseRemoteProcessGroupStatusRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseReportingTaskStatusRequest;
import static org.apache.nifi.minifi.status.StatusRequestParser.parseSystemDiagnosticsRequest;

public final class StatusConfigReporter {

    private StatusConfigReporter() {
    }

    public static FlowStatusReport getStatus(FlowController flowController, String statusRequest, Logger logger) throws StatusRequestException {
        if (statusRequest == null) {
            logger.error("Received a status request which was null");
            throw new StatusRequestException("Cannot complete status request because the statusRequest is null");
        }

        if (flowController == null) {
            logger.error("Received a status but the Flow Controller is null");
            throw new StatusRequestException("Cannot complete status request because the Flow Controller is null");
        }
        FlowStatusReport flowStatusReport = new FlowStatusReport();
        List<String> errorsGeneratingReport = new LinkedList<>();
        flowStatusReport.setErrorsGeneratingReport(errorsGeneratingReport);

        String[] itemsToReport = statusRequest.split(";");

        ProcessGroupStatus rootGroupStatus = flowController.getControllerStatus();

        Map<String, ProcessorStatus> processorStatusMap = null;
        Map<String, ConnectionStatus> connectionStatusMap = null;
        Map<String, RemoteProcessGroupStatus> remoteProcessGroupStatusMap = null;

        for (String item : itemsToReport) {
            String[] sections = item.split(":");
            try {
                switch (sections[0].toLowerCase().trim()) {
                    case "systemdiagnostics":
                        SystemDiagnosticsStatus systemDiagnosticsStatus = parseSystemDiagnosticsRequest(flowController.getSystemDiagnostics(), sections[1]);
                        flowStatusReport.setSystemDiagnosticsStatus(systemDiagnosticsStatus);
                        break;
                    case "instance":
                        InstanceStatus instanceStatus = parseInstanceRequest(sections[1], flowController, rootGroupStatus);
                        flowStatusReport.setInstanceStatus(instanceStatus);
                        break;
                    case "remoteprocessgroup":
                        if (flowStatusReport.getRemoteProcessGroupStatusList() == null) {
                            List<RemoteProcessGroupStatusBean> remoteProcessGroupStatusList = new LinkedList<>();
                            flowStatusReport.setRemoteProcessGroupStatusList(remoteProcessGroupStatusList);
                        }
                        handleRemoteProcessGroupRequest(sections, rootGroupStatus, flowController, flowStatusReport.getRemoteProcessGroupStatusList(), remoteProcessGroupStatusMap, logger);
                        break;
                    case "processor":
                        if (flowStatusReport.getProcessorStatusList() == null) {
                            List<ProcessorStatusBean> processorStatusList = new LinkedList<>();
                            flowStatusReport.setProcessorStatusList(processorStatusList);
                        }
                        handleProcessorRequest(sections, rootGroupStatus, flowController, flowStatusReport.getProcessorStatusList(), processorStatusMap, logger);
                        break;
                    case "connection":
                        if (flowStatusReport.getConnectionStatusList() == null) {
                            List<ConnectionStatusBean> connectionStatusList = new LinkedList<>();
                            flowStatusReport.setConnectionStatusList(connectionStatusList);
                        }
                        handleConnectionRequest(sections, rootGroupStatus, flowStatusReport.getConnectionStatusList(), connectionStatusMap, logger);
                        break;
                    case "provenancereporting":
                        if (flowStatusReport.getRemoteProcessGroupStatusList() == null) {
                            List<ReportingTaskStatus> reportingTaskStatusList = new LinkedList<>();
                            flowStatusReport.setReportingTaskStatusList(reportingTaskStatusList);
                        }
                        handleReportingTaskRequest(sections, flowController, flowStatusReport.getReportingTaskStatusList(), logger);
                        break;
                    case "controllerservices":
                        if (flowStatusReport.getControllerServiceStatusList() == null) {
                            List<ControllerServiceStatus> controllerServiceStatusList = new LinkedList<>();
                            flowStatusReport.setControllerServiceStatusList(controllerServiceStatusList);
                        }
                        handleControllerServices(sections, flowController, flowStatusReport.getControllerServiceStatusList(), logger);
                        break;
                }
            } catch (Exception e) {
                logger.error("Hit exception while requesting status for item '" + item + "'", e);
                errorsGeneratingReport.add("Unable to get status for request '" + item + "' due to:" + e);
            }
        }
        return flowStatusReport;
    }

    private static void handleControllerServices(String[] sections, FlowController flowController, List<ControllerServiceStatus> controllerServiceStatusList, Logger logger) {

        Collection<ControllerServiceNode> controllerServiceNodeSet = flowController.getAllControllerServices();

        if (!controllerServiceNodeSet.isEmpty()) {
            for (ControllerServiceNode controllerServiceNode : controllerServiceNodeSet) {
                controllerServiceStatusList.add(parseControllerServiceStatusRequest(controllerServiceNode, sections[1], flowController, logger));
            }
        }
    }

    private static void handleProcessorRequest(String[] sections, ProcessGroupStatus rootGroupStatus, FlowController flowController, List<ProcessorStatusBean> processorStatusBeanList,
                                               Map<String, ProcessorStatus> processorStatusMap, Logger logger) throws StatusRequestException {
        if (processorStatusMap == null) {
            processorStatusMap = transformStatusCollection(rootGroupStatus.getProcessorStatus());
        }

        String rootGroupId = flowController.getRootGroupId();
        if (sections[1].equalsIgnoreCase("all")) {
            if (!processorStatusMap.isEmpty()) {
                for (ProcessorStatus processorStatus : processorStatusMap.values()) {
                    Collection<ValidationResult> validationResults = flowController.getGroup(rootGroupId).getProcessor(processorStatus.getId()).getValidationErrors();
                    processorStatusBeanList.add(parseProcessorStatusRequest(processorStatus, sections[2], flowController, validationResults));
                }
            }
        } else {

            if (processorStatusMap.containsKey(sections[1])) {
                ProcessorStatus processorStatus = processorStatusMap.get(sections[1]);
                Collection<ValidationResult> validationResults = flowController.getGroup(rootGroupId).getProcessor(processorStatus.getId()).getValidationErrors();
                processorStatusBeanList.add(parseProcessorStatusRequest(processorStatus, sections[2], flowController, validationResults));
            } else {
                logger.warn("Status for processor with key " + sections[1] + " was requested but one does not exist");
                throw new StatusRequestException("No processor with key " + sections[1] + " to report status on");
            }
        }
    }

    private static void handleConnectionRequest(String[] sections, ProcessGroupStatus rootGroupStatus, List<ConnectionStatusBean> connectionStatusList,
                                                Map<String, ConnectionStatus> connectionStatusMap, Logger logger) throws StatusRequestException {
        if (connectionStatusMap == null) {
            connectionStatusMap = transformStatusCollection(rootGroupStatus.getConnectionStatus());
        }

        if (sections[1].equalsIgnoreCase("all")) {
            if (!connectionStatusMap.isEmpty()) {
                for (ConnectionStatus connectionStatus : connectionStatusMap.values()) {
                    connectionStatusList.add(parseConnectionStatusRequest(connectionStatus, sections[2], logger));
                }
            }
        } else {
            if (connectionStatusMap.containsKey(sections[1])) {
                connectionStatusList.add(parseConnectionStatusRequest(connectionStatusMap.get(sections[1]), sections[2], logger));
            } else {
                logger.warn("Status for connection with key " + sections[1] + " was requested but one does not exist");
                throw new StatusRequestException("No connection with key " + sections[1] + " to report status on");
            }
        }

    }

    private static void handleRemoteProcessGroupRequest(String[] sections, ProcessGroupStatus rootGroupStatus, FlowController flowController,
                                                        List<RemoteProcessGroupStatusBean> remoteProcessGroupStatusList, Map<String, RemoteProcessGroupStatus> remoteProcessGroupStatusMap,
                                                        Logger logger) throws StatusRequestException {
        if (remoteProcessGroupStatusMap == null) {
            remoteProcessGroupStatusMap = transformStatusCollection(rootGroupStatus.getRemoteProcessGroupStatus());
        }

        if (sections[1].equalsIgnoreCase("all")) {
            if (!remoteProcessGroupStatusMap.isEmpty()) {
                for (RemoteProcessGroupStatus remoteProcessGroupStatus : remoteProcessGroupStatusMap.values()) {
                    remoteProcessGroupStatusList.add(parseRemoteProcessGroupStatusRequest(remoteProcessGroupStatus, sections[2], flowController));
                }
            }
        } else {

            if (remoteProcessGroupStatusMap.containsKey(sections[1])) {
                RemoteProcessGroupStatus remoteProcessGroupStatus = remoteProcessGroupStatusMap.get(sections[1]);
                remoteProcessGroupStatusList.add(parseRemoteProcessGroupStatusRequest(remoteProcessGroupStatus, sections[2], flowController));
            } else {
                logger.warn("Status for Remote Process Group with key " + sections[1] + " was requested but one does not exist");
                throw new StatusRequestException("No Remote Process Group with key " + sections[1] + " to report status on");
            }
        }
    }

    private static void handleReportingTaskRequest(String[] sections, FlowController flowController, List<ReportingTaskStatus> reportingTaskStatusList, Logger logger) {
        Set<ReportingTaskNode> reportingTaskNodes = flowController.getAllReportingTasks();

        if (!reportingTaskNodes.isEmpty()) {
            for (ReportingTaskNode reportingTaskNode : reportingTaskNodes) {
                reportingTaskStatusList.add(parseReportingTaskStatusRequest(reportingTaskNode.getIdentifier(), reportingTaskNode, sections[1], flowController, logger));
            }
        }
    }

    private static <E> Map<String, E> transformStatusCollection(Collection<E> statusCollection) {
        Map<String, E> statusMap = new HashMap<>();
        for (E status : statusCollection) {
            if (status instanceof ProcessorStatus) {
                statusMap.put(((ProcessorStatus) status).getId(), status);
            } else if (status instanceof ConnectionStatus) {
                statusMap.put(((ConnectionStatus) status).getId(), status);
            } else if (status instanceof RemoteProcessGroupStatus) {
                statusMap.put(((RemoteProcessGroupStatus) status).getId(), status);
            } else {
                // TODO
            }
        }
        return statusMap;
    }


}
