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
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthStatus;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Real Redis role and ACL changes must not pass an opted-in write health probe.
 *******************************************************************************/
class RedisWriteHealthTest
{
   /***************************************************************************
    ** Ping succeeds on replicas and SET-denied users; write health must fail.
    ***************************************************************************/
   @Test
   void detectsReadOnlyAndDeniedWritesWithoutPersistentKeys() throws Exception
   {
      try(var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var instance = RedisRuntimeTest.hostInstance();
         var settings = new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
            .withWriteHealthKeyPrefix("fixture:health:");
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("cache", settings)).produce(instance);
         var runtime = new RedisRuntimeService();
         try
         {
            runtime.start(instance);
            var client = RedisRuntimeService.connection(instance, "cache").client();
            var input = new HealthCheckInput().withQInstance(instance);
            var health = new RedisHealthCheck();
            assertEquals(HealthStatus.UP, health.run(input).getStatus());
            for(String key : client.keys("fixture:health:*"))
            {
               assertTrue(client.pttl(key) <= 1000);
               assertTrue(client.pttl(key) > 0);
            }
            assertEquals("OK", redis.execInContainer("redis-cli", "REPLICAOF", "127.0.0.1", "1").getStdout().trim());
            assertEquals("PONG", client.ping());
            assertEquals(HealthStatus.DOWN, health.run(input).getStatus());
            assertEquals("OK", redis.execInContainer("redis-cli", "REPLICAOF", "NO", "ONE").getStdout().trim());
            assertEquals(HealthStatus.UP, health.run(input).getStatus());
            assertEquals("OK", redis.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-set").getStdout().trim());
            assertEquals("PONG", client.ping());
            assertEquals(HealthStatus.DOWN, health.run(input).getStatus());
            assertEquals("OK", redis.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+set").getStdout().trim());
            assertEquals(HealthStatus.UP, health.run(input).getStatus());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            {
               while(!client.keys("fixture:health:*").isEmpty())
               {
                  Thread.sleep(25);
               }
            });
            assertEquals(0, RedisRuntimeService.connection(instance, "cache").metrics().active());
         }
         finally
         {
            runtime.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** Probe namespace is explicit and cannot contain glob patterns or URLs.
    ***************************************************************************/
   @Test
   void validatesProbePrefix()
   {
      assertNull(new RedisConnectionConfig().getWriteHealthKeyPrefix());
      for(String prefix : java.util.List.of("", "secret@host:", "health*:", "missing-colon", "a".repeat(129) + ":"))
      {
         var errors = new java.util.ArrayList<String>();
         new RedisConnectionConfig().withWriteHealthKeyPrefix(prefix).validate(errors);
         assertFalse(errors.isEmpty());
      }
      var errors = new java.util.ArrayList<String>();
      new RedisConnectionConfig().withWriteHealthKeyPrefix("staging:kof22:health:").validate(errors);
      assertTrue(errors.isEmpty());
   }
}
