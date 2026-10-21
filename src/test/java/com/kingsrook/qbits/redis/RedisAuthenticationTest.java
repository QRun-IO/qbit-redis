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

import com.kingsrook.qbits.redis.api.RedisCredentialsProvider;

import java.time.Duration;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.DefaultRedisCredentials;
import redis.clients.jedis.RedisCredentials;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Real server authentication and bounded pool waits are prerequisites for presence.
 *******************************************************************************/
class RedisAuthenticationTest
{
   static final String TEST_PASSWORD = "integration-only-password";

   /***************************************************************************
    ** A reconnecting background subscriber must refresh credentials and readiness.
    ***************************************************************************/
   @Test
   void realtimeRecoversAfterCredentialRotation() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
         .withCommand("redis-server", "--requirepass", TEST_PASSWORD).withExposedPorts(6379))
      {
         redis.start();
         var instance = RedisRuntimeTest.hostInstance();
         RotatingCredentials.owner = instance;
         RotatingCredentials.password = TEST_PASSWORD;
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("realtime", new RedisConnectionConfig()
            .withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
            .withCredentialsProvider(new QCodeReference(RotatingCredentials.class)))
            .withRealtime("realtime", "test", "rotation")).produce(instance);
         var runtime = new RedisRuntimeService();
         var transport = new RedisRealtimeRuntimeService();
         try
         {
            runtime.start(instance);
            transport.start(instance);
            var bus = (RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).orElseThrow();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
            {
               while(!bus.isListening())
               {
                  Thread.sleep(10);
               }
            });
            var events = new java.util.concurrent.LinkedBlockingQueue<com.kingsrook.qqq.backend.core.realtime.RealtimeMessage>();
            try(var subscription = bus.subscribe(events::add))
            {
               var client = RedisRuntimeService.connection(instance, "realtime").client();
               client.configSet("requirepass", "rotated-integration-only-password");
               client.getPool().clear();
               long reconnects = bus.metrics().reconnects();
               assertEquals(0, redis.execInContainer("redis-cli", "--no-auth-warning", "-a", "rotated-integration-only-password",
                  "CLIENT", "KILL", "TYPE", "pubsub").getExitCode());
               assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
               {
                  while(bus.isListening())
                  {
                     Thread.sleep(10);
                  }
               });
               var healthInput = new com.kingsrook.qqq.backend.core.health.HealthCheckInput().withQInstance(instance);
               assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.DOWN, new RedisHealthCheck().run(healthInput).getStatus());
               RotatingCredentials.password = "rotated-integration-only-password";
               assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
               {
                  while(!bus.isListening() || bus.metrics().reconnects() <= reconnects)
                  {
                     Thread.sleep(10);
                  }
               });
               assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UP, new RedisHealthCheck().run(healthInput).getStatus());
               bus.publish(new com.kingsrook.qqq.backend.core.realtime.RealtimeMessage().withChannel("table:document").withEvent("after-rotation"));
               assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
               {
                  while(true)
                  {
                     var event = events.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                     if(event != null && "after-rotation".equals(event.getEvent()))
                     {
                        break;
                     }
                  }
               });
            }
         }
         finally
         {
            transport.stop();
            runtime.afterApplicationStop();
            RotatingCredentials.owner = null;
            RotatingCredentials.password = null;
         }
      }
   }

   /***************************************************************************
    ** New connections resolve refreshed credentials in their owning app context.
    ***************************************************************************/
   @Test
   void rotatedCredentialsUseOwnerContext() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
         .withCommand("redis-server", "--requirepass", TEST_PASSWORD).withExposedPorts(6379))
      {
         redis.start();
         var instance = RedisRuntimeTest.hostInstance();
         RotatingCredentials.owner = instance;
         RotatingCredentials.password = TEST_PASSWORD;
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig()
            .withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
            .withCredentialsProvider(new QCodeReference(RotatingCredentials.class)))).produce(instance);
         var runtime = new RedisRuntimeService();
         try
         {
            runtime.start(instance);
            var client = RedisRuntimeService.connection(instance, "cache").client();
            client.configSet("requirepass", "rotated-integration-only-password");
            RotatingCredentials.password = "rotated-integration-only-password";
            client.getPool().clear();
            var foreign = RedisRuntimeTest.hostInstance();
            var session = new com.kingsrook.qqq.backend.core.model.session.QSession();
            com.kingsrook.qqq.backend.core.context.QContext.init(foreign, session);
            assertEquals("PONG", client.ping());
            assertSame(foreign, com.kingsrook.qqq.backend.core.context.QContext.getQInstance());
            assertSame(session, com.kingsrook.qqq.backend.core.context.QContext.getQSession());
            client.getPool().clear();
            RotatingCredentials.password = null;
            for(int attempt = 0; attempt < 3; attempt++)
            {
               var failure = assertThrows(redis.clients.jedis.exceptions.JedisException.class, client::ping);
               for(Throwable cause = failure; cause != null; cause = cause.getCause())
               {
                  assertFalse(cause.toString().contains("test-secret-provider-payload"));
               }
               assertSame(foreign, com.kingsrook.qqq.backend.core.context.QContext.getQInstance());
               assertSame(session, com.kingsrook.qqq.backend.core.context.QContext.getQSession());
               assertEquals(0, client.getPool().getNumActive());
            }
            assertEquals(1, redis.execInContainer("redis-cli", "--no-auth-warning", "-a", "rotated-integration-only-password",
               "CLIENT", "LIST").getStdout().lines().count(), "Only the inspecting CLI connection should remain");
            RotatingCredentials.password = "rotated-integration-only-password";
            assertEquals("PONG", client.ping());
         }
         finally
         {
            runtime.afterApplicationStop();
            com.kingsrook.qqq.backend.core.context.QContext.clear();
            RotatingCredentials.owner = null;
            RotatingCredentials.password = null;
         }
      }
   }

   /***************************************************************************
    ** Test secret source depends on owner metadata, never a requesting user's context.
    ***************************************************************************/
   public static class RotatingCredentials implements RedisCredentialsProvider
   {
      static com.kingsrook.qqq.backend.core.model.metadata.QInstance owner;
      static volatile String password;

      /***********************************************************************
       ** Resolve current disposable credentials only in the application context.
       ***********************************************************************/
      @Override
      public RedisCredentials getCredentials()
      {
         if(com.kingsrook.qqq.backend.core.context.QContext.getQInstance() != owner
            || com.kingsrook.qqq.backend.core.context.QContext.getQSession() != null)
         {
            throw (new IllegalStateException("Unexpected credential resolution context"));
         }
         if(password == null)
         {
            throw (new IllegalStateException("test-secret-provider-payload"));
         }
         return (new DefaultRedisCredentials("default", password.toCharArray()));
      }
   }

   /***************************************************************************
    ** Require valid credentials, preserve TLS intent, and recover after pool exhaustion.
    ***************************************************************************/
   @Test
   void authenticationAndPoolExhaustion() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
         .withCommand("redis-server", "--requirepass", TEST_PASSWORD).withExposedPorts(6379))
      {
         redis.start();
         var settings = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379))
            .withTls(false).withMaximumConnections(1).withMaximumWaitMillis(100).withSocketTimeoutMillis(250)
            .withCredentialsProvider(new QCodeReference(ValidCredentials.class));
         var instance = RedisRuntimeTest.hostInstance();
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence", settings)).produce(instance);
         var runtime = new RedisRuntimeService();
         try
         {
            runtime.start(instance);
            var connection = RedisRuntimeService.connection(instance, "presence");
            assertEquals("PONG", connection.client().ping());
            try(var held = connection.client().getPool().getResource())
            {
               assertEquals(1, connection.metrics().active());
               long started = System.nanoTime();
               assertThrows(redis.clients.jedis.exceptions.JedisException.class, () -> connection.client().ping());
               assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0,
                  "An exhausted pool must honor its bounded wait");
            }
            assertEquals("PONG", connection.client().ping());
            assertEquals(0, connection.metrics().active());
            long initialClients = clientCount(redis);

            var bad = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379))
               .withTls(false).withCredentialsProvider(new QCodeReference(WrongCredentials.class));
            assertRejected(bad);
            assertEquals(initialClients, clientCount(redis));
            bad.withCredentialsProvider(null);
            assertRejected(bad);
            assertEquals(initialClients, clientCount(redis));

            // Default TLS must never silently fall back to plaintext on this server.
            bad.withTls(true).withConnectionTimeoutMillis(250).withSocketTimeoutMillis(250)
               .withCredentialsProvider(new QCodeReference(ValidCredentials.class));
            assertRejected(bad);
            assertEquals("PONG", connection.client().ping());
         }
         finally
         {
            runtime.stop();
            runtime.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** Failed authentication must not publish an unusable pool or reveal credentials.
    ***************************************************************************/
   private static void assertRejected(RedisConnectionConfig settings) throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence", settings)).produce(instance);
      QException failure = assertThrows(QException.class, () -> new RedisRuntimeService().start(instance));
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains(TEST_PASSWORD));
      assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "presence"));
   }

   /***************************************************************************
    ** Include the short-lived CLI client consistently when comparing connections.
    ***************************************************************************/
   private static long clientCount(GenericContainer<?> redis) throws Exception
   {
      return (redis.execInContainer("redis-cli", "--no-auth-warning", "-a", TEST_PASSWORD, "CLIENT", "LIST").getStdout().lines().count());
   }

   /***************************************************************************
    ** Disposable integration credentials; never credentials for an external service.
    ***************************************************************************/
   public static class ValidCredentials implements RedisCredentialsProvider
   {
      /***********************************************************************
       ** Supply credentials for the disposable test server.
       ***********************************************************************/
      @Override
      public RedisCredentials getCredentials()
      {
         return (new DefaultRedisCredentials("default", TEST_PASSWORD.toCharArray()));
      }
   }

   /***************************************************************************
    ** Deliberately incorrect credentials exercise authentication failure cleanup.
    ***************************************************************************/
   public static class WrongCredentials implements RedisCredentialsProvider
   {
      /***********************************************************************
       ** Supply a rejected test password.
       ***********************************************************************/
      @Override
      public RedisCredentials getCredentials()
      {
         return (new DefaultRedisCredentials("default", "incorrect-test-password".toCharArray()));
      }
   }
}
