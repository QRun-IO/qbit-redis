/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.kingsrook.qbits.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;

/*******************************************************************************
 ** Standard permissioned widgets for this node's Redis runtime observations.
 *******************************************************************************/
public class RedisAdminWidgets extends AbstractWidgetRenderer
{
   /***************************************************************************
    ** Register ordinary framework widgets; health remains owned by health checks.
    ***************************************************************************/
   public static void define(QInstance instance)
   {
      for(String name : List.of("redisConnections", "redisRealtime", "redisHealthHistory"))
      {
         instance.addWidget(new QWidgetMetaData().withName(name)
            .withLabel(switch(name)
            {
               case "redisConnections" -> "Redis connections (this node)";
               case "redisRealtime" -> "Redis realtime (this node)";
               default -> "Redis health history";
            })
            .withType(WidgetType.TABLE.getType()).withGridColumns(12)
            .withCodeReference(new QCodeReference(RedisAdminWidgets.class)).withPermissionRules(readPermissions()));
      }
      instance.addApp(new QAppMetaData().withName("redis").withLabel("Redis")
         .withPermissionRules(readPermissions()).withWidgets(List.of("redisConnections", "redisRealtime", "redisHealthHistory")));
   }

   /***************************************************************************
    ** The same service access permission protects navigation and widget data.
    ***************************************************************************/
   public static QPermissionRules readPermissions()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("redisView"));
   }

   /***************************************************************************
    ** Read local counters only: rendering never opens a connection or pings Redis.
    ***************************************************************************/
   @Override
   public RenderWidgetOutput render(RenderWidgetInput input) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      QInstance instance = QContext.getQInstance();
      if("redisHealthHistory".equals(input.getWidgetMetaData().getName()))
      {
         return (healthHistory(input, instance));
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      List<String> columns;
      if("redisConnections".equals(input.getWidgetMetaData().getName()))
      {
         columns = List.of("Connection", "Runtime", "Active", "Idle", "Waiting", "Maximum");
         var connections = RedisRuntimeService.connections(instance);
         var config = instance.getQBits().values().stream().map(bit -> bit.getConfig())
            .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast).findFirst().orElseThrow();
         for(String name : config.getConnections().keySet())
         {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Connection", name);
            var connection = connections.get(name);
            row.put("Runtime", connection == null ? "Not started" : "Started");
            if(connection != null)
            {
               var metrics = connection.metrics();
               row.put("Active", metrics.active());
               row.put("Idle", metrics.idle());
               row.put("Waiting", metrics.waiting());
               row.put("Maximum", metrics.maximum());
            }
            rows.add(row);
         }
      }
      else if("redisRealtime".equals(input.getWidgetMetaData().getName()))
      {
         columns = List.of("Subscription", "Queued", "Dropped", "Publish failures", "Reconnects");
         var bus = RealtimeBus.getIfStarted(instance).orElse(null);
         if(bus instanceof RedisRealtimeBus redis)
         {
            var metrics = redis.metrics();
            rows.add(Map.of("Subscription", redis.isListening() ? "Listening" : "Unavailable", "Queued", metrics.queued(),
               "Dropped", metrics.dropped(), "Publish failures", metrics.publishFailures(), "Reconnects", metrics.reconnects()));
         }
         else
         {
            boolean configured = instance.getQBits().values().stream().map(bit -> bit.getConfig())
               .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast)
               .anyMatch(config -> config.getRealtimeTopic() != null);
            rows.add(Map.of("Subscription", configured ? "Unavailable" : "Not configured"));
         }
      }
      else
      {
         throw (new IllegalArgumentException("Unknown Redis widget"));
      }
      return (new RenderWidgetOutput(new TableData(input.getWidgetMetaData().getLabel(), columns.stream()
         .map(column -> new TableData.Column("text", column, column, null, "left")).toList(), rows)
         .withNoRowsFoundHTML("No Redis realtime transport is running on this node").withRowsPerPage(20)));
   }

   /***************************************************************************
    ** Read bounded persisted framework results with explicit table enforcement,
    ** including when called inside the authorized widget action scope.
    ***************************************************************************/
   private RenderWidgetOutput healthHistory(RenderWidgetInput input, QInstance instance) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      String checks = com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider.CHECK_TABLE;
      String results = com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider.RESULT_TABLE;
      List<String> columns = List.of("status", "message", "startedAt", "finishedAt", "latencyMs", "runnerPod", "isTransition");
      List<Map<String, Object>> rows = new ArrayList<>();
      String empty = "No Redis health results have been recorded";
      String checkLink = null;
      if(instance.getTable(checks) == null || instance.getTable(results) == null)
      {
         empty = "Health history is not configured";
      }
      else
      {
         var query = new com.kingsrook.qqq.backend.core.actions.tables.QueryAction();
         var checkInput = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput().withTableName(checks)
            .withFilter(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()
               .withCriteria(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria("name",
                  com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.EQUALS, "redis.connections")).withLimit(1));
         checkInput.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
         var checkRows = query.execute(checkInput).getRecords();
         if(!checkRows.isEmpty())
         {
            var checkId = checkRows.getFirst().getValue("id");
            if(checkId != null)
            {
               checkLink = "/app/" + checks + "/" + java.net.URLEncoder.encode(checkId.toString(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var resultInput = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput().withTableName(results)
               .withFilter(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()
                  .withCriteria(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria("healthCheckId",
                     com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.EQUALS, checkRows.getFirst().getValue("id")))
                  .withOrderBy(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy("startedAt", false)).withLimit(20));
            resultInput.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
            for(var record : query.execute(resultInput).getRecords())
            {
               Map<String, Object> row = new LinkedHashMap<>();
               for(String column : columns)
               {
                  row.put(column, record.getValue(column));
               }
               rows.add(row);
            }
         }
      }
      return (new RenderWidgetOutput(new TableData(input.getWidgetMetaData().getLabel(), columns.stream()
         .map(column -> new TableData.Column("text", column, column, null, "left")).toList(), rows)
         .withNoRowsFoundHTML(empty).withRowsPerPage(20).withLinkURL(checkLink).withLinkText("View health check")));
   }
}
