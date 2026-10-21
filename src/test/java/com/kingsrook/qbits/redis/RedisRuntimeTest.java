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

import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.RedisCredentials;
import redis.clients.jedis.RedisClient;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Exercise producer validation and real pool ownership through application startup.
 *******************************************************************************/
class RedisRuntimeTest
{
   /***************************************************************************
    ** Invalid and duplicate installation must not append runtime services.
    ***************************************************************************/
   @Test
   void validatesBeforeRegistration() throws Exception
   {
      QInstance instance = new QInstance();
      assertTrue(new RedisConnectionConfig().getTls());
      assertThrows(QException.class, () -> new RedisQBitProducer().produce(instance));
      assertThrows(QException.class, () -> new RedisQBitProducer().withQBitConfig(new RedisQBitConfig()).produce(instance));
      var invalid = new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig().withSocketTimeoutMillis(0));
      assertThrows(QException.class, () -> new RedisQBitProducer().withQBitConfig(invalid).produce(instance));
      var config = new RedisQBitConfig().withConnection("cache", new RedisConnectionConfig());
      assertThrows(IllegalArgumentException.class, () -> config.withConnection("cache", new RedisConnectionConfig()));
      var producer = new RedisQBitProducer().withQBitConfig(config);
      producer.produce(instance);
      assertEquals(1, instance.getQBits().size());
      assertEquals(1, instance.getRuntimeServices().size());
      assertThrows(QException.class, () -> producer.produce(instance));
      assertEquals(1, instance.getRuntimeServices().size());
      assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "cache"));
   }

   /***************************************************************************
    ** Registered pools survive service stop, then close after dependent services.
    ***************************************************************************/
   @Test
   void lifecycleAndFailedStartup() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var settings = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false);
         QInstance instance = hostInstance();
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence", settings)).produce(instance);
         RedisRuntimeService service = new RedisRuntimeService();
         var healthInput = new com.kingsrook.qqq.backend.core.health.HealthCheckInput().withQInstance(instance);
         assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UNKNOWN, new RedisHealthCheck().run(healthInput).getStatus());
         try
         {
            service.start(instance);
            RedisClient client = RedisRuntimeService.connection(instance, "presence").client();
            assertEquals(7L, com.kingsrook.qbits.redis.api.RedisAccess.scripts(instance, "presence").execute(
               new com.kingsrook.qbits.redis.api.RedisScript("return 7"), java.util.List.of(), java.util.List.of()));
            assertThrows(IllegalStateException.class, () -> com.kingsrook.qbits.redis.api.RedisAccess.scripts(instance, "missing"));
            var cache = com.kingsrook.qbits.redis.api.RedisAccess.cache(instance, "presence",
               new com.kingsrook.qbits.redis.api.RedisNamespace("test", "app", "tenant", "cache"), 64);
            cache.put("key", new byte[] {1}, java.time.Duration.ofSeconds(30));
            assertArrayEquals(new byte[] {1}, cache.get("key").orElseThrow());
            assertEquals("PONG", client.ping());
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UP, new RedisHealthCheck().run(healthInput).getStatus());
            assertEquals(16, RedisRuntimeService.connection(instance, "presence").metrics().maximum());
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(new QInstance(), "presence"));
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "unknown"));
            assertThrows(QException.class, () -> service.start(instance));
            assertThrows(QException.class, () -> new RedisRuntimeService().start(instance));
            assertEquals("PONG", client.ping());
            long before = redis.execInContainer("redis-cli", "CLIENT", "LIST").getStdout().lines().count();
            QInstance failedInstance = hostInstance();
            var failing = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
               .withCredentialsProvider(new QCodeReference(FailingCredentials.class));
            new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("first", settings).withConnection("second", failing)).produce(failedInstance);
            QException failure = assertThrows(QException.class, () -> new RedisRuntimeService().start(failedInstance));
            assertFalse(failure.toString().contains("test-secret"));
            assertNull(failure.getCause());
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(failedInstance, "first"));
            assertEquals(before, redis.execInContainer("redis-cli", "CLIENT", "LIST").getStdout().lines().count());
            service.stop();
            assertEquals("PONG", client.ping());
            service.afterApplicationStop();
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "presence"));
            assertThrows(RuntimeException.class, client::ping);
            service.afterApplicationStop();
            service.start(instance);
            assertNotSame(client, RedisRuntimeService.connection(instance, "presence").client());
            redis.stop();
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.DOWN, new RedisHealthCheck().run(healthInput).getStatus());
         }
         finally
         {
            service.stop();
            service.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** A host credential provider can fail with secret-bearing exception text.
    ***************************************************************************/
   public static class FailingCredentials implements RedisCredentialsProvider
   {
      /************************************************************************
       ** Simulate a secret-provider failure without real credentials.
       ************************************************************************/
      @Override
      public RedisCredentials getCredentials()
      {
         throw (new IllegalStateException("test-secret must not escape startup"));
      }
   }

   /***************************************************************************
    ** Runtime starts inside a validated host application, not an empty instance.
    ***************************************************************************/
   static QInstance hostInstance()
   {
      var instance = new QInstance();
      instance.addBackend(new com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData()
         .withName("memory").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData()
         .withName("test").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.FULLY_ANONYMOUS));
      instance.addTable(new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName("record").withBackendName("memory").withPrimaryKeyField("id")
         .withField(new com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData("id", com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType.INTEGER)));
      return (instance);
   }
}
