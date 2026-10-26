/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026.  Kingsrook, LLC
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
import org.testcontainers.containers.Network;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.exceptions.JedisException;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Controlled promotion at an explicit endpoint, not discovery or durable sessions.
 *******************************************************************************/
class RedisPromotionTest
{
   /***************************************************************************
    ** Two unchanged node pools can use a promoted replica and resubscribe there.
    ***************************************************************************/
   @Test
   void explicitReplicaEndpointSupportsControlledPromotion() throws Exception
   {
      try(var network = Network.newNetwork();
          var primary = new GenericContainer<>("redis:7.4-alpine").withNetwork(network).withNetworkAliases("primary").withExposedPorts(6379);
          var replica = new GenericContainer<>("redis:7.4-alpine").withNetwork(network).withExposedPorts(6379)
             .withCommand("redis-server", "--replicaof", "primary", "6379"))
      {
         primary.start();
         replica.start();
         var firstInstance = host(replica);
         var secondInstance = host(replica);
         var firstRuntime = new RedisRuntimeService();
         var secondRuntime = new RedisRuntimeService();
         try(var oldPrimary = new RedisConnection(new HostAndPort(primary.getHost(), primary.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).build(), 2, Duration.ofMillis(500)))
         {
            try
            {
               firstRuntime.start(firstInstance);
               secondRuntime.start(secondInstance);
               var firstConnection = RedisRuntimeService.connection(firstInstance, "realtime");
               var secondConnection = RedisRuntimeService.connection(secondInstance, "realtime");
               assertEquals("OK", oldPrimary.client().set("promotion-marker", "before"));
               assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
               {
                  while(!"before".equals(firstConnection.client().get("promotion-marker")))
                  {
                     Thread.sleep(25);
                  }
               });
               assertThrows(JedisException.class, () -> firstConnection.client().set("replica-write", "forbidden"));
               assertNull(oldPrimary.client().get("replica-write"));

               var events = new LinkedBlockingQueue<RealtimeMessage>();
               try(var first = new RedisRealtimeBus(firstInstance); var second = new RedisRealtimeBus(secondInstance))
               {
                  second.subscribe(events::add);
                  assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                  {
                     while(!first.isListening() || !second.isListening())
                     {
                        Thread.sleep(25);
                     }
                  });
                  primary.stop();
                  assertThrows(JedisException.class, () -> oldPrimary.client().set("old-primary-write", "forbidden"));
                  long promotionStarted = System.nanoTime();
                  var promoted = replica.execInContainer("redis-cli", "REPLICAOF", "NO", "ONE");
                  assertEquals(0, promoted.getExitCode());
                  assertEquals("OK", promoted.getStdout().trim());
                  assertEquals("before", secondConnection.client().get("promotion-marker"));
                  assertEquals("OK", secondConnection.client().set("promotion-marker", "after"));
                  assertEquals("after", firstConnection.client().get("promotion-marker"));

                  var disconnected = replica.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub");
                  assertEquals(0, disconnected.getExitCode());
                  assertEquals("2", disconnected.getStdout().trim());
                  assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                  {
                     while(!first.isListening() || !second.isListening()
                        || first.metrics().reconnects() == 0 || second.metrics().reconnects() == 0)
                     {
                        Thread.sleep(25);
                     }
                  });
                  events.clear();
                  first.publish(new RealtimeMessage().withChannel("table:document").withEvent("after-promotion"));
                  assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                  {
                     while(true)
                     {
                        var message = events.poll(100, TimeUnit.MILLISECONDS);
                        if(message != null && "after-promotion".equals(message.getEvent()))
                        {
                           break;
                        }
                     }
                  });
                  var recoveryTime = Duration.ofNanos(System.nanoTime() - promotionStarted);
                  assertTrue(recoveryTime.compareTo(Duration.ofSeconds(25)) < 0);
                  java.util.logging.Logger.getLogger(RedisPromotionTest.class.getName())
                     .info("Controlled promotion, subscription reconnect and event delivery: " + recoveryTime.toMillis() + " ms");
                  for(var connection : java.util.List.of(firstConnection, secondConnection))
                  {
                     assertEquals(2, connection.metrics().maximum());
                     assertTrue(connection.metrics().active() <= 2);
                     assertEquals(0, connection.metrics().waiting());
                  }
               }
               assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
               {
                  while(firstConnection.metrics().active() != 0 || secondConnection.metrics().active() != 0)
                  {
                     Thread.sleep(25);
                  }
               });
            }
            finally
            {
               firstRuntime.afterApplicationStop();
               secondRuntime.afterApplicationStop();
            }
         }
      }
   }


   /***************************************************************************
    ** One subscription slot and one command slot per independently configured node.
    ***************************************************************************/
   private static QInstance host(GenericContainer<?> endpoint) throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      var settings = new RedisConnectionConfig().withHost(endpoint.getHost()).withPort(endpoint.getMappedPort(6379)).withTls(false)
         .withMaximumConnections(2).withConnectionTimeoutMillis(500).withSocketTimeoutMillis(1000);
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("realtime", settings)
         .withRealtime("realtime", "test", "promotion")).produce(instance);
      return (instance);
   }
}
