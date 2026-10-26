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

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Independent node pools, real Pub/Sub, reconnect and application isolation.
 *******************************************************************************/
class RedisRealtimeBusTest
{
   /***************************************************************************
    ** Command connectivity alone cannot make realtime ready or start it implicitly.
    ***************************************************************************/
   @Test
   void readinessAndRuntimeOwnership() throws Exception
   {
      try(var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var instance = host(redis, "readiness");
         var pools = new RedisRuntimeService();
         var transport = new RedisRealtimeRuntimeService();
         var health = new RedisHealthCheck();
         var input = new com.kingsrook.qqq.backend.core.health.HealthCheckInput().withQInstance(instance);
         try
         {
            pools.start(instance);
            assertEquals("PONG", RedisRuntimeService.connection(instance, "realtime").client().ping());
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.DOWN, health.run(input).getStatus());
            assertTrue(com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).isEmpty());
            transport.start(instance);
            var bus = (RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).orElseThrow();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            {
               while(!bus.isListening())
               {
                  Thread.sleep(10);
               }
            });
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UP, health.run(input).getStatus());
            com.kingsrook.qqq.backend.core.context.QContext.withTemporaryContext(
               new com.kingsrook.qqq.backend.core.context.CapturedContext(instance,
                  new com.kingsrook.qqq.backend.core.model.session.QSession().withPermission("redisView.hasAccess")), () ->
               {
                  var action = new com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction();
                  var connections = (com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData) action.execute(
                     new com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput()
                        .withWidgetMetaData(instance.getWidget("redisConnections"))).getWidgetData();
                  assertEquals("Started", connections.getRows().getFirst().get("Runtime"));
                  assertEquals(1, connections.getRows().getFirst().get("Active"));
                  var realtime = (com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData) action.execute(
                     new com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput()
                        .withWidgetMetaData(instance.getWidget("redisRealtime"))).getWidgetData();
                  assertEquals("Listening", realtime.getRows().getFirst().get("Subscription"));
                  assertEquals(0, realtime.getRows().getFirst().get("Queued"));
               });
            assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
            {
               while(bus.metrics().heartbeats() == 0)
               {
                  Thread.sleep(10);
               }
            }, () -> "No heartbeat received; transport=" + bus.metrics()
               + ", pool=" + RedisRuntimeService.connection(instance, "realtime").metrics());
            // Keep sockets open but suppress server command progress beyond the read deadline.
            redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "17000", "ALL");
            assertTimeoutPreemptively(Duration.ofSeconds(17), () ->
            {
               while(bus.isListening())
               {
                  Thread.sleep(10);
               }
            });
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.DOWN, health.run(input).getStatus());
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
            {
               while(!bus.isListening())
               {
                  Thread.sleep(10);
               }
            });
            assertTrue(bus.metrics().reconnects() > 0);
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UP, health.run(input).getStatus());
            assertThrows(com.kingsrook.qqq.backend.core.exceptions.QException.class, () -> transport.start(instance));
            transport.stop();
            assertFalse(bus.isListening());
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.DOWN, health.run(input).getStatus());
            assertEquals("PONG", RedisRuntimeService.connection(instance, "realtime").client().ping());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            {
               while(RedisRuntimeService.connection(instance, "realtime").metrics().active() != 0)
               {
                  Thread.sleep(10);
               }
            });
            transport.start(instance);
            var restarted = (RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).orElseThrow();
            assertNotSame(bus, restarted);
            pools.afterApplicationStop();
            assertFalse(restarted.isListening());
            assertTrue(com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).isEmpty());
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UNKNOWN, health.run(input).getStatus());
         }
         finally
         {
            transport.stop();
            pools.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** Broker echoes once per node, retains audience, and signals subscriber gaps.
    ***************************************************************************/
   @Test
   void crossNodeReconnectAndIsolation() throws Exception
   {
      try(var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var firstInstance = host(redis, "app");
         var secondInstance = host(redis, "app");
         var otherInstance = host(redis, "other");
         var firstRuntime = new RedisRuntimeService();
         var secondRuntime = new RedisRuntimeService();
         var otherRuntime = new RedisRuntimeService();
         try
         {
            firstRuntime.start(firstInstance);
            secondRuntime.start(secondInstance);
            otherRuntime.start(otherInstance);
            var firstEvents = new LinkedBlockingQueue<RealtimeMessage>();
            var secondEvents = new LinkedBlockingQueue<RealtimeMessage>();
            var otherEvents = new LinkedBlockingQueue<RealtimeMessage>();
            try(var first = new RedisRealtimeBus(firstInstance); var second = new RedisRealtimeBus(secondInstance);
               var other = new RedisRealtimeBus(otherInstance))
            {
               first.subscribe(firstEvents::add);
               second.subscribe(secondEvents::add);
               other.subscribe(otherEvents::add);
               assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
               {
                  while(!first.isListening() || !second.isListening() || !other.isListening())
                  {
                     Thread.sleep(10);
                  }
               });
               var sent = new RealtimeMessage().withChannel("table:document").withEvent("presence.invalidated")
                  .withAudience("tenant-a").withData(java.util.Map.of("table", "document"));
               first.publish(sent);
               var one = awaitEvent(firstEvents, "presence.invalidated");
               var two = awaitEvent(secondEvents, "presence.invalidated");
               assertEquals(sent.getId(), one.getId());
               assertEquals(sent.getId(), two.getId());
               assertEquals("tenant-a", two.getAudience());
               assertEquals(sent.getData(), two.getData());
               assertNull(otherEvents.stream().filter(event -> "presence.invalidated".equals(event.getEvent())).findFirst().orElse(null));
               assertEquals(1, RedisRuntimeService.connection(firstInstance, "realtime").metrics().active());
               firstEvents.clear();
               secondEvents.clear();
               var entered = new java.util.concurrent.CountDownLatch(1);
               var release = new java.util.concurrent.CountDownLatch(1);
               try(var slow = second.subscribe(message ->
               {
                  if("slow".equals(message.getEvent()))
                  {
                     entered.countDown();
                     try
                     {
                        release.await(10, TimeUnit.SECONDS);
                     }
                     catch(InterruptedException interrupted)
                     {
                        Thread.currentThread().interrupt();
                     }
                  }
               }))
               {
                  first.publish(new RealtimeMessage().withChannel("table:document").withEvent("slow"));
                  assertTrue(entered.await(5, TimeUnit.SECONDS));
                  for(int i = 0; i < 400; i++)
                  {
                     first.publish(new RealtimeMessage().withChannel("table:document").withEvent("burst"));
                  }
                  assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                  {
                     while(second.metrics().dropped() == 0)
                     {
                        Thread.sleep(10);
                     }
                  });
                  assertTrue(second.metrics().queued() <= 256);
               }
               finally
               {
                  release.countDown();
               }
               awaitEvent(secondEvents, "resync");
               // Drain the retained bounded burst before checking reconnect behavior.
               assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
               {
                  while(second.metrics().queued() > 0)
                  {
                     Thread.sleep(10);
                  }
               });
               first.publish(new RealtimeMessage().withChannel("table:document").withEvent("drained"));
               awaitEvent(secondEvents, "drained");
               firstEvents.clear();
               secondEvents.clear();
               redis.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub");
               awaitEvent(secondEvents, "resync");
               assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
               {
                  while(!first.isListening() || !second.isListening())
                  {
                     Thread.sleep(10);
                  }
               });
               first.publish(new RealtimeMessage().withChannel("table:document").withEvent("after-reconnect"));
               awaitEvent(secondEvents, "after-reconnect");
               // No direct local echo or per-node republishing.
               var extra = secondEvents.poll(300, TimeUnit.MILLISECONDS);
               assertTrue(extra == null || "resync".equals(extra.getEvent()));
               assertTrue(otherEvents.stream().allMatch(event -> "resync".equals(event.getEvent())));
               redis.stop();
               assertDoesNotThrow(() -> first.publish(sent));
               awaitEvent(firstEvents, "resync");
               assertTimeoutPreemptively(Duration.ofSeconds(3), first::close);
               assertFalse(first.isListening());
            }
         }
         finally
         {
            firstRuntime.afterApplicationStop();
            secondRuntime.afterApplicationStop();
            otherRuntime.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** Skip control hints while retaining a bounded wait for the expected event.
    ***************************************************************************/
   private static RealtimeMessage awaitEvent(LinkedBlockingQueue<RealtimeMessage> queue, String event) throws InterruptedException
   {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while(System.nanoTime() < deadline)
      {
         var message = queue.poll(100, TimeUnit.MILLISECONDS);
         if(message != null && event.equals(message.getEvent()))
         {
            return (message);
         }
      }
      fail("Expected realtime event: " + event);
      return (null);
   }

   /***************************************************************************
    ** Each node owns a two-connection pool: one subscriber and one command slot.
    ***************************************************************************/
   private static QInstance host(GenericContainer<?> redis, String application) throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      var settings = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
         .withMaximumConnections(2).withConnectionTimeoutMillis(200).withSocketTimeoutMillis(200);
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("realtime", settings)
         .withRealtime("realtime", "test", application)).produce(instance);
      return (instance);
   }
}
