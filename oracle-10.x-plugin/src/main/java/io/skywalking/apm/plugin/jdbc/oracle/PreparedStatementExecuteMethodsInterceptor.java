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
 *
 */

package io.skywalking.apm.plugin.jdbc.oracle;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.StringTag;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig;
import org.apache.skywalking.apm.plugin.jdbc.PreparedStatementParameterBuilder;
import org.apache.skywalking.apm.plugin.jdbc.define.StatementEnhanceInfos;
import org.apache.skywalking.apm.plugin.jdbc.trace.ConnectionInfo;

/**
 * @author zhang xin
 */
public class PreparedStatementExecuteMethodsInterceptor implements InstanceMethodsAroundInterceptor {
  
    public static final StringTag SQL_PARAMETERS = new StringTag("db.sql.parameters");
    public static final StringTag SQL_RESULTS = new StringTag("db.sql.results");

    /**
     * Add a new section to trace sql parameter.
     */
    @Override
    public final void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes,
        MethodInterceptResult result) throws Throwable {
        StatementEnhanceInfos cacheObject = (StatementEnhanceInfos)objInst.getSkyWalkingDynamicField();
        if (cacheObject != null && cacheObject.getConnectionInfo() != null) {
            ConnectionInfo connectInfo = cacheObject.getConnectionInfo();
            AbstractSpan span = ContextManager.createExitSpan(buildOperationName(connectInfo, method.getName(), cacheObject.getStatementName()), connectInfo.getDatabasePeer());
            Tags.DB_TYPE.set(span, connectInfo.getDBType());
            Tags.DB_INSTANCE.set(span, connectInfo.getDatabaseName());
            Tags.DB_STATEMENT.set(span, cacheObject.getSql());
            span.setComponent(connectInfo.getComponent());

            //tarce sql parameters. 
            if(JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS){
              Object[] parameters = cacheObject.getParameters();
              if(parameters!=null && parameters.length>0){
                int maxIndex = cacheObject.getMaxIndex();
                String parameterString = getParameterString(parameters, maxIndex);
                SQL_PARAMETERS.set(span, parameterString);
              }
            }

            SpanLayer.asDB(span);
        }
    }

    @Override
    public final Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes,
        Object ret) throws Throwable {
        StatementEnhanceInfos cacheObject = (StatementEnhanceInfos)objInst.getSkyWalkingDynamicField();

        if (cacheObject != null && cacheObject.getConnectionInfo() != null) {
            
            if (ret != null) {
                if ("executeBatch".equals(method.getName())) {
                    int[] updateCounts = (int[]) ret;
                    SQL_RESULTS.set(ContextManager.activeSpan(), "[" + String.join(",", String.valueOf(updateCounts.length),
                            String.valueOf(Arrays.stream(updateCounts).sum())) + "]");
                } else if ("executeUpdate".equals(method.getName())) {
                    SQL_RESULTS.set(ContextManager.activeSpan(), "[" + ret + "]");
                }
            }
            
            ContextManager.stopSpan();
        }

        return ret;
    }

    @Override 
    public final void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes, Throwable t) {
        StatementEnhanceInfos cacheObject = (StatementEnhanceInfos)objInst.getSkyWalkingDynamicField();
        if (cacheObject != null && cacheObject.getConnectionInfo() != null) {
            ContextManager.activeSpan().errorOccurred().log(t);
        }
    }

    private String buildOperationName(ConnectionInfo connectionInfo, String methodName, String statementName) {
        return connectionInfo.getDBType() + "/JDBC/" + statementName + "/" + methodName;
    }


    private String getParameterString(Object[] parameters, int maxIndex) {
      return new PreparedStatementParameterBuilder()
          .setParameters(parameters)
          .setMaxIndex(maxIndex)
          .build();
    }
}
