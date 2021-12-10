/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.batch.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.dtstack.batch.common.convert.BinaryConversion;
import com.dtstack.batch.engine.rdbms.service.impl.Engine2DTOService;
import com.dtstack.engine.common.env.EnvironmentContext;
import com.dtstack.batch.common.exception.ErrorCode;
import com.dtstack.batch.common.exception.RdosDefineException;
import com.dtstack.engine.domain.BatchTask;
import com.dtstack.batch.domain.BatchTaskParamShade;
import com.dtstack.batch.domain.BatchTaskVersionDetail;
import com.dtstack.batch.engine.rdbms.common.util.SqlFormatterUtil;
import com.dtstack.batch.enums.DeployModeEnum;
import com.dtstack.batch.enums.EScheduleType;
import com.dtstack.batch.enums.YarnAppLogType;
import com.dtstack.batch.schedule.JobParamReplace;
import com.dtstack.batch.service.task.impl.BatchTaskParamShadeService;
import com.dtstack.batch.service.task.impl.BatchTaskService;
import com.dtstack.batch.service.task.impl.BatchTaskVersionService;
import com.dtstack.batch.vo.BatchServerLogVO;
import com.dtstack.batch.vo.SyncErrorCountInfoVO;
import com.dtstack.batch.vo.SyncStatusLogInfoVO;
import com.dtstack.batch.web.server.vo.result.BatchServerLogByAppLogTypeResultVO;
import com.dtstack.dtcenter.common.enums.*;
import com.dtstack.dtcenter.common.util.Base64Util;
import com.dtstack.dtcenter.common.util.DataFilter;
import com.dtstack.dtcenter.common.util.JsonUtils;
import com.dtstack.dtcenter.common.util.MathUtil;
import com.dtstack.engine.domain.ScheduleJob;
import com.dtstack.engine.domain.ScheduleTaskShade;
import com.dtstack.engine.master.vo.action.ActionJobEntityVO;
import com.dtstack.engine.master.vo.action.ActionLogVO;
import com.dtstack.engine.master.vo.action.ActionRetryLogVO;
import com.dtstack.engine.master.impl.ActionService;
import com.dtstack.engine.master.impl.ClusterService;
import com.dtstack.engine.master.impl.ComponentService;
import com.dtstack.engine.master.impl.ScheduleTaskShadeService;
import com.dtstack.engine.common.metric.batch.IMetric;
import com.dtstack.engine.common.metric.batch.MetricBuilder;
import com.dtstack.engine.common.metric.prometheus.PrometheusMetricQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.math3.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.*;

@Service
public class BatchServerLogService {

    private static final Logger logger = LoggerFactory.getLogger(BatchServerLogService.class);

    @Resource(name = "batchJobParamReplace")
    private JobParamReplace jobParamReplace;

    @Autowired
    private BatchTaskParamShadeService batchTaskParamShadeService;

    @Autowired
    private BatchDownloadService batchDownloadService;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private BatchTaskVersionService batchTaskVersionService;

    @Autowired
    private com.dtstack.engine.master.impl.ScheduleJobService ScheduleJobService;

    @Autowired
    private BatchTaskService batchTaskService;

    @Autowired
    private ScheduleTaskShadeService scheduleTaskShadeService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private ComponentService componentService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DOWNLOAD_LOG = "/api/rdos/download/batch/batchDownload/downloadJobLog?jobId=%s&taskType=%s&projectId=%s";
    private static final String DOWNLOAD_TYPE_LOG = "/api/rdos/download/batch/batchDownload/downloadAppTypeLog?jobId=%s&logType=%s&projectId=%s";

    private static final List<Integer> finish_status = new ArrayList<>();

    private static final int SECOND_LENGTH = 10;
    private static final int MILLIS_LENGTH = 13;
    private static final int MICRO_LENGTH = 16;
    private static final int NANOS_LENGTH = 19;

    static {
        BatchServerLogService.finish_status.add(TaskStatus.FINISHED.getStatus());
        BatchServerLogService.finish_status.add(TaskStatus.FAILED.getStatus());
    }

    public BatchServerLogVO getLogsByJobId(String jobId, Integer pageInfo) {

        if (StringUtils.isBlank(jobId)) {
            return null;
        }

        final ScheduleJob job = this.ScheduleJobService.getByJobId(jobId, null);
        if (Objects.isNull(job)) {
            BatchServerLogService.logger.info("can not find job by id:{}.", jobId);
            throw new RdosDefineException(ErrorCode.CAN_NOT_FIND_JOB);
        }
        final Long tenantId = job.getTenantId();

        final ScheduleTaskShade scheduleTaskShade = this.scheduleTaskShadeService.findTaskId(job.getTaskId(), null, AppType.RDOS.getType());
        if (Objects.isNull(scheduleTaskShade)) {
            BatchServerLogService.logger.info("can not find task shade  by jobId:{}.", jobId);
            throw new RdosDefineException(ErrorCode.SERVER_EXCEPTION);
        }

        final BatchServerLogVO batchServerLogVO = new BatchServerLogVO();

        //日志从engine获取
        final JSONObject logsBody = new JSONObject(2);
        logsBody.put("jobId", jobId);
        logsBody.put("computeType", ComputeType.BATCH.getType());
        ActionLogVO actionLogVO = actionService.log(jobId, ComputeType.BATCH.getType());
        JSONObject info = new JSONObject();
        if (!Strings.isNullOrEmpty(actionLogVO.getLogInfo())) {
            try {
                info = JSON.parseObject(actionLogVO.getLogInfo());
            } catch (final Exception e) {
                BatchServerLogService.logger.error("parse jobId {} } logInfo error {}", jobId, actionLogVO.getLogInfo());
                info.put("msg_info", actionLogVO.getLogInfo());
            }
        }

        if (null != job.getVersionId()) {
            // 需要获取执行任务时候版本对应的sql
            final BatchTaskVersionDetail taskVersion = this.batchTaskVersionService.getByVersionId((long) job.getVersionId());
            if (null != taskVersion) {
                if (StringUtils.isEmpty(taskVersion.getOriginSql())){
                    String jsonSql = StringUtils.isEmpty(taskVersion.getSqlText())?"{}":taskVersion.getSqlText();
                    scheduleTaskShade.setSqlText(jsonSql);
                }else {
                    scheduleTaskShade.setSqlText(taskVersion.getOriginSql());
                }
            }

        }

        info.put("status", job.getStatus());
        if (EJobType.SPARK_SQL.getVal().equals(scheduleTaskShade.getTaskType()) || EJobType.LIBRA_SQL.getVal().equals(scheduleTaskShade.getTaskType())
                || EJobType.ORACLE_SQL.getVal().equals(scheduleTaskShade.getTaskType()) || EJobType.TIDB_SQL.getVal().equals(scheduleTaskShade.getTaskType())
                || EJobType.IMPALA_SQL.getVal().equals(scheduleTaskShade.getTaskType())) {
            // 处理sql注释，先把注释base64编码，再处理非注释的自定义参数
            String sql = SqlFormatterUtil.dealAnnotationBefore(scheduleTaskShade.getSqlText());
            final List<BatchTaskParamShade> taskParamsToReplace = this.batchTaskParamShadeService.getTaskParam(scheduleTaskShade.getId());
            sql = this.jobParamReplace.paramReplace(sql, taskParamsToReplace, job.getCycTime());
            sql = SqlFormatterUtil.dealAnnotationAfter(sql);
            info.put("sql", sql);
        } else if (EJobType.SYNC.getVal().equals(scheduleTaskShade.getTaskType())) {
            final JSONObject jobJson;
            //taskShade 需要解码
            JSONObject sqlJson = null;
            try {
                sqlJson = JSON.parseObject(Base64Util.baseDecode(scheduleTaskShade.getSqlText()));
            } catch (final Exception e) {
                sqlJson = JSON.parseObject(scheduleTaskShade.getSqlText());
            }
            jobJson = sqlJson.getJSONObject("job");

            // 密码脱敏

            DataFilter.passwordFilter(jobJson);

            String jobStr = jobJson.toJSONString();
            final List<BatchTaskParamShade> taskParamsToReplace = this.batchTaskParamShadeService.getTaskParam(scheduleTaskShade.getId());
            jobStr = this.jobParamReplace.paramReplace(jobStr, taskParamsToReplace, job.getCycTime());
            info.put("sql", JsonUtils.formatJSON(jobStr));
            if (Objects.nonNull(job.getExecEndTime()) && Objects.nonNull(job.getExecStartTime())) {
                List<ActionJobEntityVO> engineEntities = actionService.entitys(Collections.singletonList(logsBody.getString("jobId")));
                String engineJobId = "";
                if (CollectionUtils.isNotEmpty(engineEntities)) {
                    engineJobId =  engineEntities.get(0).getEngineJobId();
                }
                this.parseIncreInfo(info, jobStr, tenantId, engineJobId, job.getExecStartTime().getTime(), job.getExecEndTime().getTime(),scheduleTaskShade.getTaskParams());
            }
        }

        if (job.getJobId() != null) {
            try {
                if (StringUtils.isNotBlank(actionLogVO.getEngineLog())) {
                    final Map<String, Object> engineLogMap = BatchServerLogService.objectMapper.readValue(actionLogVO.getEngineLog(), Map.class);
                    this.dealPerfLog(engineLogMap);
                    info.putAll(engineLogMap);

                    // 去掉统计信息，界面不展示，调度端统计使用
                    info.remove("countInfo");
                }
            } catch (final Exception e) {
                // 非json格式的日志也返回
                info.put("msg_info", actionLogVO.getEngineLog());
                BatchServerLogService.logger.error("", e);
            }
        }

        // 增加重试日志
        final String retryLog = this.buildRetryLog(jobId, pageInfo, batchServerLogVO);
        this.formatForLogInfo(info, job.getType(),scheduleTaskShade.getTaskType(), retryLog, null,
                null, null, batchServerLogVO, tenantId,jobId);

        if (!scheduleTaskShade.getTaskType().equals(EJobType.SYNC.getVal())
                && !scheduleTaskShade.getTaskType().equals(EJobType.VIRTUAL.getVal())
                && !scheduleTaskShade.getTaskType().equals(EJobType.WORK_FLOW.getVal())
                && !scheduleTaskShade.getTaskType().equals(EJobType.ALGORITHM_LAB.getVal())
                && finish_status.contains(job.getStatus())) {
            batchServerLogVO.setDownloadLog(String.format(DOWNLOAD_LOG, jobId, scheduleTaskShade.getTaskType(),0L));
        }

        batchServerLogVO.setName(scheduleTaskShade.getName());
        batchServerLogVO.setComputeType(scheduleTaskShade.getComputeType());
        batchServerLogVO.setTaskType(scheduleTaskShade.getTaskType());

        return batchServerLogVO;
    }


    /**
     * 处理性能指标日志
     *
     * @param engineLogMap
     */
    private void dealPerfLog(final Map<String, Object> engineLogMap) {

        if (!engineLogMap.containsKey("perf")) {
            return;
        }

        String str = (String) engineLogMap.get("perf");
        //转换成汉文加数字的字符串数组
        final String[] strings = str.split("\n");

        final StringBuilder temp = new StringBuilder();
        for (int i = 0; i < strings.length; i++) {
            // //转换成汉文和数字的俩个元素的字符串数组
            final String[] s = strings[i].split("\t");
            if (i == 2 || i == 5) {
                s[1] = BinaryConversion.getPrintSize(Long.parseLong(s[1]), true);
            } else {
                s[1] = String.format("%,d", Long.parseLong(s[1]));
            }

            //字符串数组重新转换成字符串
            strings[i] = s[0] + "\t" + s[1];
            temp.append(strings[i]).append("\n");
        }

        str = temp.toString();
        engineLogMap.put("perf", str);
    }


    /**
     * 组装重试日志
     *
     * @param jobId
     * @param pageInfo         第几次的重试日志 如果传入0  默认是最新的  -1 展示所有的（为了兼容内部调用）
     * @param batchServerLogVO
     * @return
     */
    private String buildRetryLog(final String jobId, Integer pageInfo, final BatchServerLogVO batchServerLogVO) {
        final Map<String, Object> retryParamsMap = Maps.newHashMap();
        retryParamsMap.put("jobId", jobId);
        retryParamsMap.put("computeType", ComputeType.BATCH.getType());
        //先获取engine的日志总数信息
        List<ActionRetryLogVO> actionRetryLogVOs = actionService.retryLog(jobId);
        if (CollectionUtils.isEmpty(actionRetryLogVOs)) {
            return "";
        }
        batchServerLogVO.setPageSize(actionRetryLogVOs.size());
        if(Objects.isNull(pageInfo)){
            pageInfo = 0;
        }
        //engine 的 retryNum 从1 开始
        if (0 == pageInfo) {
            pageInfo = actionRetryLogVOs.size();
        }
        if (pageInfo > actionRetryLogVOs.size()) {
            throw new RdosDefineException(ErrorCode.INVALID_PARAMETERS);
        }
        retryParamsMap.put("retryNum", pageInfo);
        //获取对应的日志
        ActionRetryLogVO retryLogContent = actionService.retryLogDetail(jobId, pageInfo);
        StringBuilder builder = new StringBuilder();
        if (Objects.isNull(retryLogContent)) {
            return "";
        }
        Integer retryNumVal = retryLogContent.getRetryNum();
        int retryNum = 0;
        if(Objects.nonNull(retryNumVal)){
            retryNum = retryNumVal + 1;
        }
        String logInfo = retryLogContent.getLogInfo();
        String engineInfo = retryLogContent.getEngineLog();
        String retryTaskParams = retryLogContent.getRetryTaskParams();
        builder.append("====================第 ").append(retryNum).append("次重试====================").append("\n");

        if (!Strings.isNullOrEmpty(logInfo)) {
            builder.append("====================LogInfo start====================").append("\n");
            builder.append(logInfo).append("\n");
            builder.append("=====================LogInfo end=====================").append("\n");
        }
        if (!Strings.isNullOrEmpty(engineInfo)) {
            builder.append("==================EngineInfo  start==================").append("\n");
            builder.append(engineInfo).append("\n");
            builder.append("===================EngineInfo  end===================").append("\n");
        }
        if (!Strings.isNullOrEmpty(retryTaskParams)) {
            builder.append("==================RetryTaskParams  start==================").append("\n");
            builder.append(retryTaskParams).append("\n");
            builder.append("===================RetryTaskParams  end===================").append("\n");
        }

        builder.append("==================第").append(retryNum).append("次重试结束==================").append("\n");
        for (int j = 0; j < 10; j++) {
            builder.append("==" + "\n");
        }

        return builder.toString();
    }

    /**
     * 解析增量同步信息
     */
    private void parseIncreInfo(final JSONObject info, final String job, final Long dtUicTenantId, final String jobId, final long startTime, final long endTime, final String taskParams) {
        if (StringUtils.isEmpty(jobId)) {
            return;
        }
        try {
            final DeployModeEnum deployModeEnum = DeployModeEnum.parseDeployTypeByTaskParams(taskParams);
            final String enginePluginInfo = Engine2DTOService.getEnginePluginInfo(dtUicTenantId, MultiEngineType.HADOOP.getType());
            final JSONObject jsonObject = JSON.parseObject(enginePluginInfo);
            final JSONObject flinkJsonObject = jsonObject.getJSONObject(EComponentType.FLINK.getTypeCode() + "");
            final String prometheusHost = flinkJsonObject.getJSONObject(deployModeEnum.getName()).getString("prometheusHost");
            final String prometheusPort = flinkJsonObject.getJSONObject(deployModeEnum.getName()).getString("prometheusPort");
            //prometheus的配置信息 从控制台获取
            final PrometheusMetricQuery prometheusMetricQuery = new PrometheusMetricQuery(String.format("%s:%s", prometheusHost, prometheusPort));
            final IMetric startLocationMetric = MetricBuilder.buildMetric("startLocation", jobId, startTime, endTime, prometheusMetricQuery);
            final IMetric endLocationMetric = MetricBuilder.buildMetric("endLocation", jobId, startTime, endTime, prometheusMetricQuery);
            String startLocation = null;
            String endLocation = null;
            if (startLocationMetric != null) {
                startLocation = String.valueOf(startLocationMetric.getMetric());
            }
            if (Objects.nonNull(endLocationMetric)) {
                endLocation = String.valueOf(endLocationMetric.getMetric());
            }

            if (StringUtils.isBlank(job)) {
                return;
            }
            final JSONObject jobJson = JSON.parseObject(job);
            final String increColumn = (String) JSONPath.eval(jobJson, "$.job.content[0].reader.parameter.increColumn");

            final String table = (String) JSONPath.eval(jobJson, "$.job.content[0].reader.parameter.connection[0].table[0]");
            final StringBuilder increStrBuild = new StringBuilder();
            increStrBuild.append("数据表:  \t").append(table).append("\n");
            increStrBuild.append("增量标识:\t").append(increColumn).append("\n");

            if (StringUtils.isEmpty(endLocation) || endLocation.startsWith("-")) {
                increStrBuild.append("开始位置:\t").append("同步数据条数为0").append("\n");
                info.put("increInfo", increStrBuild.toString());
                return;
            }

            boolean isDateCol = false;

            final JSONArray columns = (JSONArray) JSONPath.eval(jobJson, "$.job.content[0].reader.parameter.column");
            for (final Object column : columns) {
                if (column instanceof JSONObject) {
                    final String name = ((JSONObject) column).getString("name");
                    if (name != null && name.equals(increColumn)) {
                        final String type = ((JSONObject) column).getString("type");
                        Boolean typeCheck = type != null && (type.matches("(?i)date|datetime|time") || type.toLowerCase().contains("timestamp"));
                        if (typeCheck) {
                            isDateCol = true;
                        }
                    }
                }
            }

            if (StringUtils.isEmpty(startLocation)) {
                startLocation = "全量同步";
            }

            if (isDateCol) {
                startLocation = this.formatLongStr(startLocation);
                endLocation = this.formatLongStr(endLocation);
            }

            increStrBuild.append("开始位置:\t").append(startLocation).append("\n");
            increStrBuild.append("结束位置:\t").append(endLocation).append("\n");
            info.put("increInfo", increStrBuild.toString());
        } catch (final Exception e) {
            BatchServerLogService.logger.warn("{}", e);
        }
    }

    private String formatLongStr(final String longStr) {
        if (StringUtils.isEmpty(longStr)) {
            return "";
        }

        if ("0".equalsIgnoreCase(longStr)){
            return "";
        }
        if(!NumberUtils.isNumber(longStr)){
            return longStr;
        }

        final long time = Long.parseLong(longStr);

        final Timestamp ts = new Timestamp(this.getMillis(time));
        ts.setNanos(this.getNanos(time));

        return this.getNanosTimeStr(ts.toString());
    }

    private String getNanosTimeStr(String timeStr) {
        if (timeStr.length() < 29) {
            timeStr += org.apache.commons.lang.StringUtils.repeat("0", 29 - timeStr.length());
        }

        return timeStr;
    }

    private int getNanos(final long startLocation) {
        final String timeStr = String.valueOf(startLocation);
        final int nanos;
        if (timeStr.length() == BatchServerLogService.SECOND_LENGTH) {
            nanos = 0;
        } else if (timeStr.length() == BatchServerLogService.MILLIS_LENGTH) {
            nanos = Integer.parseInt(timeStr.substring(BatchServerLogService.SECOND_LENGTH, BatchServerLogService.MILLIS_LENGTH)) * 1000000;
        } else if (timeStr.length() == BatchServerLogService.MICRO_LENGTH) {
            nanos = Integer.parseInt(timeStr.substring(BatchServerLogService.SECOND_LENGTH, BatchServerLogService.MICRO_LENGTH)) * 1000;
        } else if (timeStr.length() == BatchServerLogService.NANOS_LENGTH) {
            nanos = Integer.parseInt(timeStr.substring(BatchServerLogService.SECOND_LENGTH, BatchServerLogService.NANOS_LENGTH));
        } else {
            throw new IllegalArgumentException("Unknown time unit:startLocation=" + startLocation);
        }

        return nanos;
    }

    private long getMillis(final long startLocation) {
        final String timeStr = String.valueOf(startLocation);
        final long millisSecond;
        if (timeStr.length() == BatchServerLogService.SECOND_LENGTH) {
            millisSecond = startLocation * 1000;
        } else if (timeStr.length() == BatchServerLogService.MILLIS_LENGTH) {
            millisSecond = startLocation;
        } else if (timeStr.length() == BatchServerLogService.MICRO_LENGTH) {
            millisSecond = startLocation / 1000;
        } else if (timeStr.length() == BatchServerLogService.NANOS_LENGTH) {
            millisSecond = startLocation / 1000000;
        } else {
            throw new IllegalArgumentException("Unknown time unit:startLocation=" + startLocation);
        }

        return millisSecond;
    }

    public BatchServerLogVO.SyncJobInfo parseExecLog(final String perf, final Long execTime) {
        if (Strings.isNullOrEmpty(perf)) {
            return new BatchServerLogVO.SyncJobInfo();
        }

        final String[] arr = perf.split("\\n");
        final BatchServerLogVO.SyncJobInfo syncJobInfo = new BatchServerLogVO.SyncJobInfo();
        Integer readNum = 0;
        Integer errorNum = 0;
        Integer writeNum = 0;

        for (final String tmp : arr) {
            if (tmp.contains("读取记录数:")) {
                readNum = this.parseNumFromLog(tmp);
            } else if (tmp.contains("错误记录数:")) {
                errorNum = this.parseNumFromLog(tmp);
            } else if (tmp.contains("写入记录数:")) {
                writeNum = this.parseNumFromLog(tmp);
            }
        }

        syncJobInfo.setReadNum(readNum);
        syncJobInfo.setWriteNum(writeNum);
        syncJobInfo.setExecTime(execTime);
        if (errorNum == null || readNum == null) {
            syncJobInfo.setDirtyPercent(0F);
        } else {
            if (readNum == 0) {
                syncJobInfo.setDirtyPercent(0F);
            } else {
                syncJobInfo.setDirtyPercent(Float.valueOf(errorNum) / Float.valueOf(readNum) * 100);
            }
        }
        return syncJobInfo;
    }

    private Integer parseNumFromLog(final String tmp) {
        return MathUtil.getIntegerVal(tmp.split("\t")[1].trim().replace(",", ""));
    }

    private void formatForLogInfo(final JSONObject jobInfo, final Integer jobType, final Integer taskType, final String retryLog, final Timestamp startTime,
                                  final Timestamp endTime, final Long execTime, final BatchServerLogVO batchServerLogVO, final Long dtUicTenantId, final String jobId) {
        if (!taskType.equals(EJobType.SYNC.getVal())) {
            if (jobInfo.containsKey("engineLogErr")) {
                // 有这个字段表示日志没有获取到，目前engine端只对flink任务做了这种处理，这里先提前加上
                jobInfo.put("msg_info", jobInfo.getString("engineLogErr"));
            } else {
                final String msgInfo = Optional.ofNullable(jobInfo.getString("msg_info")).orElse("");
                jobInfo.put("msg_info", msgInfo + "\n" + retryLog);
            }

            batchServerLogVO.setLogInfo(jobInfo.toJSONString());
            return;
        }

        this.formatForSyncLogInfo(jobInfo, jobType,retryLog, startTime, endTime, execTime, batchServerLogVO, dtUicTenantId,jobId);
    }

    private void formatForSyncLogInfo(final JSONObject jobInfo, final Integer jobType, final String retryLog, final Timestamp startTime,
                                      final Timestamp endTime, final Long execTime, final BatchServerLogVO batchServerLogVO, final Long dtUicTenantId, final String jobId) {

        try {
            final Map<String, Object> sqlInfoMap = (Map<String, Object>) BatchServerLogService.objectMapper.readValue(jobInfo.getString("sql"), Object.class);
            final JSONObject res = new JSONObject();
            res.put("job", sqlInfoMap.get("job"));
            res.put("parser", sqlInfoMap.get("parser"));
            res.put("createModel", sqlInfoMap.get("createModel"));

            final Map<String, Object> jobInfoMap = (Map<String, Object>) BatchServerLogService.objectMapper.readValue(jobInfo.toString(), Object.class);

            final JSONObject logInfoJson = new JSONObject();
            logInfoJson.put("jobid", jobInfoMap.get("jobid"));
            logInfoJson.put("msg_info", jobInfoMap.get("msg_info") + retryLog);
            logInfoJson.put("turncated", jobInfoMap.get("turncated"));
            if (jobInfoMap.get("ruleLogList") != null) {
                logInfoJson.put("ruleLogList", jobInfoMap.get("ruleLogList"));
            }

            String perfLogInfo = jobInfoMap.getOrDefault("perf", StringUtils.EMPTY).toString();
            final boolean parsePerfLog = startTime != null && endTime != null
                    && jobInfoMap.get("jobid") != null && this.environmentContext.getSyncLogPromethues();

            if (parsePerfLog) {
                perfLogInfo = this.formatPerfLogInfo(jobInfoMap.get("jobid").toString(),jobId, startTime.getTime(), endTime.getTime(), dtUicTenantId);
            }

            logInfoJson.put("perf", perfLogInfo);
            //补数据没有增量标志信息
            if (EScheduleType.NORMAL_SCHEDULE.getType() == jobType){
                logInfoJson.put("increInfo", jobInfo.getString("increInfo"));
            }
            logInfoJson.put("sql", res);

            String allExceptions = "";
            if (jobInfoMap.get("root-exception") != null) {
                allExceptions = jobInfoMap.get("root-exception").toString();
                if (!Strings.isNullOrEmpty(retryLog)) {
                    allExceptions += retryLog;
                }
            }

            // 如果没有拿到日志，并且有engineLogErr属性，可能是flink挂了
            if (StringUtils.isEmpty(allExceptions.trim()) && jobInfoMap.containsKey("engineLogErr")) {
                if (!TaskStatus.FINISHED.getStatus().equals(Integer.valueOf(jobInfoMap.get("status").toString()))) {
                    //成功默认为空
                    allExceptions = jobInfoMap.get("engineLogErr").toString();
                } else {
                    allExceptions = "";
                }

            }

            logInfoJson.put("all-exceptions", allExceptions);
            logInfoJson.put("status", jobInfoMap.get("status"));

            batchServerLogVO.setLogInfo(logInfoJson.toString());

            //解析出数据同步的信息
            final BatchServerLogVO.SyncJobInfo syncJobInfo = this.parseExecLog(perfLogInfo, execTime);
            batchServerLogVO.setSyncJobInfo(syncJobInfo);
        } catch (final Exception e) {
            BatchServerLogService.logger.error("logInfo 解析失败", e);
            batchServerLogVO.setLogInfo(jobInfo.toString());
        }
    }


    public String formatPerfLogInfo(final String applicationId, final String jobId, final long startTime, final long endTime, final Long dtUicTenantId) {

        final ScheduleJob job = this.ScheduleJobService.getByJobId(jobId, null);
        if (Objects.isNull(job)) {
            BatchServerLogService.logger.info("can not find job by id:{}.", jobId);
            throw new RdosDefineException(ErrorCode.CAN_NOT_FIND_JOB);
        }
        if (job.getTaskId() == null || job.getTaskId() == -1){
            throw new RdosDefineException(ErrorCode.CAN_NOT_FIND_TASK);
        }
        BatchTask batchTaskById = batchTaskService.getBatchTaskById(job.getTaskId());
        //prometheus的配置信息 从控制台获取
        final Pair<String, String> prometheusHostAndPort = this.getPrometheusHostAndPort(dtUicTenantId,batchTaskById.getTaskParams());
        if (prometheusHostAndPort == null){
            return "promethues配置为空";
        }
        final PrometheusMetricQuery prometheusMetricQuery = new PrometheusMetricQuery(String.format("%s:%s", prometheusHostAndPort.getKey(), prometheusHostAndPort.getValue()));

        //TODO 之后查询是可以直接获取最后一条记录的方法
        //防止数据同步执行时间太长 查询prometheus的时候返回exceeded maximum resolution of 11,000 points per timeseries
        final long maxGapTime = 60 * 1000 * 60 * (long)8;
        long gapStartTime = startTime;
        if (endTime - startTime >= maxGapTime) {
            //超过11,000 points 查询1小时间隔内
            gapStartTime = endTime - 60 * 1000 * 60;
        }

        final IMetric numReadMetric = MetricBuilder.buildMetric("numRead", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric byteReadMetric = MetricBuilder.buildMetric("byteRead", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric readDurationMetric = MetricBuilder.buildMetric("readDuration", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric numWriteMetric = MetricBuilder.buildMetric("numWrite", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric byteWriteMetric = MetricBuilder.buildMetric("byteWrite", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric writeDurationMetric = MetricBuilder.buildMetric("writeDuration", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric numErrorMetric = MetricBuilder.buildMetric("nErrors", applicationId, gapStartTime, endTime, prometheusMetricQuery);
        final SyncStatusLogInfoVO formatPerfLogInfo = this.getFormatPerfLogInfo(numReadMetric, byteReadMetric, readDurationMetric, numWriteMetric, byteWriteMetric, writeDurationMetric, numErrorMetric);
        return formatPerfLogInfo.buildReadableLog();
    }

    private Pair<String,String> getPrometheusHostAndPort(final Long dtUicTenantId, final String taskParams){
        Boolean hasStandAlone = clusterService.hasStandalone(dtUicTenantId, EComponentType.FLINK.getTypeCode());
        JSONObject flinkJsonObject ;
        if (hasStandAlone) {
            String configByKey = clusterService.getConfigByKey(dtUicTenantId, EComponentType.FLINK.getConfName(), false, null);
            flinkJsonObject = JSONObject.parseObject(configByKey);
        }else {
            String enginePluginInfo = Engine2DTOService.getEnginePluginInfo(dtUicTenantId, MultiEngineType.HADOOP.getType());
            if (StringUtils.isBlank(enginePluginInfo)) {
                BatchServerLogService.logger.info("console uicTenantId {} pluginInfo is null", dtUicTenantId);
                return null;
            }
            DeployModeEnum deployModeEnum = DeployModeEnum.parseDeployTypeByTaskParams(taskParams);
            JSONObject jsonObject = JSON.parseObject(enginePluginInfo);
            flinkJsonObject = jsonObject.getJSONObject(EComponentType.FLINK.getTypeCode() + "").getJSONObject(deployModeEnum.getName());
        }
        String prometheusHost = flinkJsonObject.getString("prometheusHost");
        String prometheusPort = flinkJsonObject.getString("prometheusPort");
        if (StringUtils.isBlank(prometheusHost) || StringUtils.isBlank(prometheusPort)) {
            BatchServerLogService.logger.info("prometheus http info is blank {} {}", prometheusHost, prometheusPort);
            return null;
        }
        return new Pair<>(prometheusHost,prometheusPort);
    }

    public SyncStatusLogInfoVO getSyncJobLogInfo(final String jobId, final Long taskId, final long startTime, final long endTime, final Long dtUicTenantId){

        final ScheduleTaskShade scheduleTaskShade = this.scheduleTaskShadeService.findTaskId(taskId, null, AppType.RDOS.getType());

        final SyncStatusLogInfoVO syncStatusLogInfoVO = new SyncStatusLogInfoVO();
        final Pair<String, String> prometheusHostAndPort = this.getPrometheusHostAndPort(dtUicTenantId,scheduleTaskShade.getTaskParams());
        if (prometheusHostAndPort == null){
            return syncStatusLogInfoVO;
        }
        final PrometheusMetricQuery prometheusMetricQuery = new PrometheusMetricQuery(String.format("%s:%s", prometheusHostAndPort.getKey(), prometheusHostAndPort.getValue()));

        //TODO 之后查询是可以直接获取最后一条记录的方法
        //防止数据同步执行时间太长 查询prometheus的时候返回exceeded maximum resolution of 11,000 points per timeseries
        final long maxGapTime = 60 * 1000 * 60 * (long)8;
        long gapStartTime = startTime;
        if (endTime - startTime >= maxGapTime) {
            //超过11,000 points 查询1小时间隔内
            gapStartTime = endTime - 60 * 1000 * 60;
        }

        final IMetric numReadMetric = MetricBuilder.buildMetric("numRead", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric byteReadMetric = MetricBuilder.buildMetric("byteRead", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric readDurationMetric = MetricBuilder.buildMetric("readDuration", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric numWriteMetric = MetricBuilder.buildMetric("numWrite", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric byteWriteMetric = MetricBuilder.buildMetric("byteWrite", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric writeDurationMetric = MetricBuilder.buildMetric("writeDuration", jobId, gapStartTime, endTime, prometheusMetricQuery);
        final IMetric numErrorMetric = MetricBuilder.buildMetric("nErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        return this.getFormatPerfLogInfo(numReadMetric, byteReadMetric, readDurationMetric, numWriteMetric, byteWriteMetric, writeDurationMetric, numErrorMetric);
    }

    /**
     * 获取数据同步任务的脏数据统计信息
     * @param jobId
     * @param taskId
     * @param startTime
     * @param endTime
     * @param dtUicTenantId
     * @return
     */
    public SyncErrorCountInfoVO getSyncJobCountInfo(final String jobId, final Long taskId, final long startTime, final long endTime, final Long dtUicTenantId){
         ScheduleTaskShade scheduleTaskShade = this.scheduleTaskShadeService.findTaskId(taskId, null, AppType.RDOS.getType());

         SyncErrorCountInfoVO countInfoVO = new SyncErrorCountInfoVO();
         Pair<String, String> prometheusHostAndPort = this.getPrometheusHostAndPort(dtUicTenantId,scheduleTaskShade.getTaskParams());
        if (prometheusHostAndPort == null){
            return countInfoVO;
        }
         PrometheusMetricQuery prometheusMetricQuery = new PrometheusMetricQuery(String.format("%s:%s", prometheusHostAndPort.getKey(), prometheusHostAndPort.getValue()));

        //TODO 之后查询是可以直接获取最后一条记录的方法
        //防止数据同步执行时间太长 查询prometheus的时候返回exceeded maximum resolution of 11,000 points per timeseries
        long maxGapTime = 60 * 1000 * 60 * 8L;
        long gapStartTime = startTime;
        if (endTime - startTime >= maxGapTime) {
            //超过11,000 points 查询1小时间隔内
            gapStartTime = endTime - 60 * 1000 * 60;
        }

        IMetric nullErrorsMetric = MetricBuilder.buildMetric("nullErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        IMetric duplicateErrorsMetric = MetricBuilder.buildMetric("duplicateErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        IMetric conversionErrorsMetric = MetricBuilder.buildMetric("conversionErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        IMetric otherErrorsMetric = MetricBuilder.buildMetric("otherErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        IMetric numErrorMetric = MetricBuilder.buildMetric("nErrors", jobId, gapStartTime, endTime, prometheusMetricQuery);
        countInfoVO.setConversionErrors(getLongValue(conversionErrorsMetric.getMetric()));
        countInfoVO.setNullErrors(getLongValue(nullErrorsMetric.getMetric()));
        countInfoVO.setDuplicateErrors(getLongValue(duplicateErrorsMetric.getMetric()));
        countInfoVO.setOtherErrors(getLongValue(otherErrorsMetric.getMetric()));
        countInfoVO.setNumError(getLongValue(numErrorMetric.getMetric()));
        return countInfoVO;
    }

    private SyncStatusLogInfoVO getFormatPerfLogInfo(final IMetric numReadMetric, final IMetric byteReadMetric, final IMetric readDurationMetric,
                                                     final IMetric numWriteMetric, final IMetric byteWriteMetric, final IMetric writeDurationMetric,
                                                     final IMetric numErrorMetric){
        final SyncStatusLogInfoVO logInfoVO = new SyncStatusLogInfoVO();
        if (numReadMetric != null){
            logInfoVO.setNumRead(this.getLongValue(numReadMetric.getMetric()));
        }
        if (byteReadMetric != null){
            logInfoVO.setByteRead(this.getLongValue(byteReadMetric.getMetric()));
        }
        if (readDurationMetric != null){
            logInfoVO.setReadDuration(this.getLongValue(readDurationMetric.getMetric()));
        }
        if (numWriteMetric != null){
            logInfoVO.setNumWrite(this.getLongValue(numWriteMetric.getMetric()));
        }
        if (byteWriteMetric != null){
            logInfoVO.setByteWrite(this.getLongValue(byteWriteMetric.getMetric()));
        }
        if (writeDurationMetric != null){
            logInfoVO.setWriteDuration(this.getLongValue(writeDurationMetric.getMetric()));
        }
        if (numErrorMetric != null){
            logInfoVO.setNErrors(getLongValue(numErrorMetric.getMetric()));
        }
        return logInfoVO;
    }


    private long getLongValue(final Object obj) {
        if (obj == null) {
            return 0L;
        }

        return Long.valueOf(obj.toString());
    }


    public JSONObject getLogsByAppId(Long dtuicTenantId, Integer taskType, String jobId, Long projectId) {
        if (EJobType.SYNC.getVal().equals(taskType)
                || EJobType.VIRTUAL.getVal().equals(taskType)
                || EJobType.WORK_FLOW.getVal().equals(taskType)) {
            throw new RdosDefineException("数据同步、虚节点、工作流的任务日志不支持下载");
        }
        final JSONObject result = new JSONObject(YarnAppLogType.values().length);
        for (final YarnAppLogType type : YarnAppLogType.values()) {
            final String msg = this.batchDownloadService.downloadAppTypeLog(dtuicTenantId, jobId, 100,
                    type.name().toUpperCase(), taskType);
            final JSONObject typeLog = new JSONObject(2);
            typeLog.put("msg", msg);
            typeLog.put("download", String.format(BatchServerLogService.DOWNLOAD_TYPE_LOG, jobId, type.name().toUpperCase(),projectId));
            result.put(type.name(), typeLog);
        }

        return result;
    }

    public BatchServerLogByAppLogTypeResultVO getLogsByAppLogType(Long dtuicTenantId, Integer taskType, String jobId, String logType, Long projectId) {
        if (EJobType.SYNC.getVal().equals(taskType)
                || EJobType.VIRTUAL.getVal().equals(taskType)
                || EJobType.WORK_FLOW.getVal().equals(taskType)) {
            throw new RdosDefineException("数据同步、虚节点、工作流的任务日志不支持下载");
        }

        if (YarnAppLogType.getType(logType) == null) {
            throw new RdosDefineException("not support the logType:" + logType);
        }

        final String msg = this.batchDownloadService.downloadAppTypeLog(dtuicTenantId, jobId, 100,
                logType.toUpperCase(), taskType);
        BatchServerLogByAppLogTypeResultVO resultVO = new BatchServerLogByAppLogTypeResultVO();
        resultVO.setMsg(msg);
        resultVO.setDownload(String.format(BatchServerLogService.DOWNLOAD_TYPE_LOG, jobId, logType,projectId));

        return resultVO;
    }

}
