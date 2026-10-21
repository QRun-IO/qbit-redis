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

import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Exercise admin access through the framework rather than calling render directly.
 *******************************************************************************/
class RedisAdminWidgetsTest
{
   /***************************************************************************
    ** Health registration collisions must fail before installing runtime metadata.
    ***************************************************************************/
   @Test
   void healthCollisionPreservesHost() throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      var existing = new com.kingsrook.qqq.backend.core.health.HealthCheckMetaData().withName("redis.connections").withLabel("Host check");
      com.kingsrook.qqq.backend.core.health.HealthChecksMetaData.ofOrWithNew(instance).withCheck(existing);
      var config = new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig()).withRealtime("cache", "test", "app");
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QException.class,
         () -> new RedisQBitProducer().withQBitConfig(config).produce(instance));
      assertNull(instance.getApp("redis"));
      assertNull(instance.getWidget("redisConnections"));
      assertTrue(instance.getQBits() == null || instance.getQBits().isEmpty());
      assertTrue(instance.getRuntimeServices() == null || instance.getRuntimeServices().isEmpty());
      assertNull(com.kingsrook.qqq.backend.core.realtime.RealtimeMetaData.of(instance).getBus());
      assertEquals(java.util.List.of(existing), com.kingsrook.qqq.backend.core.health.HealthChecksMetaData.ofOrWithNew(instance).getChecks());
   }

   /***************************************************************************
    ** Rejected namespace replacement must preserve the previously valid config.
    ***************************************************************************/
   @Test
   void rejectedRealtimeConfigurationIsAtomic()
   {
      var config = new RedisQBitConfig().withRealtime("original", "test", "app");
      String topic = config.getRealtimeTopic();
      assertThrows(IllegalArgumentException.class, () -> config.withRealtime("replacement", "", "app"));
      assertEquals("original", config.getRealtimeConnection());
      assertEquals(topic, config.getRealtimeTopic());
   }

   /***************************************************************************
    ** Packaged help resolves through the framework with artifact provenance.
    ***************************************************************************/
   @Test
   void packagedHelp() throws Exception
   {
      var bundle = com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles
         .load("help/qbit-redis.yaml", getClass().getClassLoader());
      assertEquals(new RedisQBitProducer().getQBitMetaData().getVersion(), bundle.getVersion());
      assertEquals(java.util.Set.of("app:redis;slot:help", "widget:redisConnections;slot:label",
         "widget:redisRealtime;slot:label", "widget:redisHealthHistory;slot:label"),
         bundle.getEntries().stream().map(entry -> entry.getKey()).collect(java.util.stream.Collectors.toSet()));
      assertTrue(bundle.getEntries().stream().allMatch(entry -> entry.getSummary().length() <= 120 && !entry.getContent().isBlank()));
   }

   /***************************************************************************
    ** Persisted health is separately permissioned even inside widget execution.
    ***************************************************************************/
   @Test
   void frameworkHealthHistory() throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig())).produce(instance);
      QContext.init(instance, new QSession().withPermission("redisView.hasAccess"));
      try
      {
         var input = new RenderWidgetInput().withWidgetMetaData(instance.getWidget("redisHealthHistory"));
         var action = new RenderWidgetAction();
         assertEquals("Health history is not configured", ((TableData) action.execute(input).getWidgetData()).getNoRowsFoundHTML());
         new com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider().defineAll(instance, "memory", null);
         assertThrows(QPermissionDeniedException.class, () -> action.execute(input));
         QContext.setQSession(new com.kingsrook.qqq.backend.core.model.session.QSystemUserSession());
         var insert = new com.kingsrook.qqq.backend.core.actions.tables.InsertAction();
         var check = insert.execute(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput()
            .withTableName("health_check").withRecord(new com.kingsrook.qqq.backend.core.model.data.QRecord()
               .withValue("name", "redis.connections"))).getRecords().getFirst();
         for(int i = 0; i < 25; i++)
         {
            insert.execute(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput()
               .withTableName("health_check_result").withRecord(new com.kingsrook.qqq.backend.core.model.data.QRecord()
                  .withValue("healthCheckId", check.getValue("id")).withValue("slot", (long) i)
                  .withValue("status", "UP").withValue("message", "result " + i)
                  .withValue("startedAt", java.time.Instant.parse("2026-10-21T00:00:00Z").plusSeconds(i))));
         }
         insert.execute(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput()
            .withTableName("health_check_result").withRecord(new com.kingsrook.qqq.backend.core.model.data.QRecord()
               .withValue("healthCheckId", -1).withValue("slot", 100L).withValue("message", "unrelated")
               .withValue("startedAt", java.time.Instant.parse("2026-10-22T00:00:00Z"))));
         QContext.setQSession(new QSession().withPermissions("redisView.hasAccess", "health.read"));
         var data = (TableData) action.execute(input).getWidgetData();
         assertEquals(20, data.getRows().size());
         assertEquals("/app/health_check/" + check.getValue("id"), data.getLinkURL());
         assertEquals("View health check", data.getLinkText());
         var permissionInput = new RenderWidgetInput();
         permissionInput.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         assertThrows(QPermissionDeniedException.class, () ->
            com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper.checkProcessPermissionThrowing(permissionInput, "healthRunCheckNow"));
         QContext.setQSession(new QSession().withPermissions("redisView.hasAccess", "health.read", "health.write"));
         assertDoesNotThrow(() -> com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper
            .checkProcessPermissionThrowing(permissionInput, "healthRunCheckNow"));
         assertEquals("result 24", data.getRows().getFirst().get("message"));
         assertEquals("result 5", data.getRows().getLast().get("message"));
         assertFalse(data.getRows().getFirst().containsKey("detailsJson"));
         QContext.setQSession(new QSession().withPermission("redisView.hasAccess"));
         assertThrows(QPermissionDeniedException.class, () -> action.execute(input));
      }
      finally
      {
         QContext.clear();
      }
   }

   /***************************************************************************
    ** Installation must preserve host metadata, including when realtime is enabled.
    ***************************************************************************/
   @Test
   void collisionsDoNotMutateHost() throws Exception
   {
      for(String name : java.util.List.of("redis", "redisConnections", "redisRealtime", "redisHealthHistory"))
      {
         var instance = RedisRuntimeTest.hostInstance();
         if(name.equals("redis"))
         {
            instance.addApp(new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName(name).withLabel("Host app"));
         }
         else
         {
            instance.addWidget(new com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData().withName(name).withLabel("Host widget"));
         }
         var config = new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig()).withRealtime("cache", "test", "app");
         assertThrows(com.kingsrook.qqq.backend.core.exceptions.QException.class,
            () -> new RedisQBitProducer().withQBitConfig(config).produce(instance));
         assertTrue(instance.getQBits() == null || instance.getQBits().isEmpty());
         assertTrue(instance.getRuntimeServices() == null || instance.getRuntimeServices().isEmpty());
         assertNull(com.kingsrook.qqq.backend.core.realtime.RealtimeMetaData.of(instance).getBus());
         assertEquals(name.equals("redis") ? "Host app" : "Host widget",
            name.equals("redis") ? instance.getApp(name).getLabel() : instance.getWidget(name).getLabel());
      }
   }

   /***************************************************************************
    ** Configured but absent transport is visibly unavailable, not disabled.
    ***************************************************************************/
   @Test
   void absentConfiguredTransport() throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig())
         .withRealtime("cache", "test", "app")).produce(instance);
      QContext.init(instance, new QSession().withPermission("redisView.hasAccess"));
      try
      {
         var data = (TableData) new RenderWidgetAction().execute(new RenderWidgetInput()
            .withWidgetMetaData(instance.getWidget("redisRealtime"))).getWidgetData();
         assertEquals(java.util.Map.of("Subscription", "Unavailable"), data.getRows().getFirst());
         assertTrue(com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).isEmpty());
      }
      finally
      {
         QContext.clear();
      }
   }

   /***************************************************************************
    ** Anonymous/ordinary users cannot read runtime observations; allowed views
    ** do not start pools or transport and never disclose endpoint configuration.
    ***************************************************************************/
   @Test
   void permissionsAndStoppedRuntime() throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("cache",
         new RedisConnectionConfig().withHost("private-endpoint.invalid"))).produce(instance);
      QContext.init(instance, new QSession());
      try
      {
         assertEquals("redisView", instance.getApp("redis").getPermissionRules().getPermissionBaseName());
         for(String name : java.util.List.of("redisConnections", "redisRealtime", "redisHealthHistory"))
         {
            var input = new RenderWidgetInput().withWidgetMetaData(instance.getWidget(name));
            input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
            assertThrows(QPermissionDeniedException.class, () -> new RenderWidgetAction().execute(input));
         }
         QContext.setQSession(new QSession().withPermission("redisView.hasAccess"));
         var connections = (TableData) new RenderWidgetAction().execute(new RenderWidgetInput()
            .withWidgetMetaData(instance.getWidget("redisConnections"))).getWidgetData();
         assertEquals(1, connections.getRows().size());
         assertEquals("Not started", connections.getRows().getFirst().get("Runtime"));
         assertEquals(java.util.Set.of("Connection", "Runtime"), connections.getRows().getFirst().keySet());
         var realtime = (TableData) new RenderWidgetAction().execute(new RenderWidgetInput()
            .withWidgetMetaData(instance.getWidget("redisRealtime"))).getWidgetData();
         assertEquals("Not configured", realtime.getRows().getFirst().get("Subscription"));
         assertTrue(RedisRuntimeService.connections(instance).isEmpty());
         assertTrue(com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).isEmpty());
         assertThrows(IllegalArgumentException.class, () -> new RedisAdminWidgets().render(new RenderWidgetInput()
            .withWidgetMetaData(new com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData().withName("unknown"))));
      }
      finally
      {
         QContext.clear();
      }
   }
}
