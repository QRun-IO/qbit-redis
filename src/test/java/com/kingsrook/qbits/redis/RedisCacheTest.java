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

import com.kingsrook.qbits.redis.api.RedisCache;

import com.kingsrook.qbits.redis.api.RedisNamespace;

import java.time.Duration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Real Redis verifies expiry and cross-client sharing rather than mocked commands.
 *******************************************************************************/
class RedisCacheTest
{
   /***************************************************************************
    ** Independent node pools share values, while tenants and expired keys isolate.
    ***************************************************************************/
   @Test
   void sharedCacheExpiryIsolationAndOutage() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var endpoint = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).build();
         try(var nodeA = new RedisConnection(endpoint, config, 2, Duration.ofMillis(500));
             var nodeB = new RedisConnection(endpoint, config, 2, Duration.ofMillis(500)))
         {
            var scope = new RedisNamespace("test", "app", "tenant-a", "cache");
            var first = new RedisCache(nodeA.client(), scope, 64);
            var second = new RedisCache(nodeB.client(), scope, 64);
            var otherTenant = new RedisCache(nodeB.client(), new RedisNamespace("test", "app", "tenant-b", "cache"), 64);
            byte[] value = {0, 1, -1};
            first.put("record", value, Duration.ofSeconds(10));
            assertArrayEquals(value, second.get("record").orElseThrow());
            assertTrue(otherTenant.get("record").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> first.put("record", new byte[65], Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> first.put("record", value, Duration.ZERO));
            assertArrayEquals(value, second.get("record").orElseThrow());
            assertArrayEquals(value, second.getAndTouch("record", Duration.ofSeconds(20)).orElseThrow());
            assertTrue(nodeA.client().pttl(scope.key("record")) > 10000);
            assertTrue(first.touch("record", Duration.ofSeconds(30)));
            assertTrue(nodeA.client().pttl(scope.key("record")) > 20000);
            assertThrows(IllegalArgumentException.class, () -> first.touch("record", Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> first.getAndTouch("record", Duration.ofNanos(1)));
            assertTrue(second.remove("record"));
            assertTrue(first.getAndTouch("record", Duration.ofSeconds(1)).isEmpty());
            assertFalse(first.touch("record", Duration.ofSeconds(1)));
            assertFalse(first.remove("record"));
            first.put("expiring", value, Duration.ofMillis(100));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while(second.get("expiring").isPresent() && System.nanoTime() < deadline)
            {
               Thread.sleep(20);
            }
            assertTrue(second.get("expiring").isEmpty());
            redis.stop();
            assertThrows(redis.clients.jedis.exceptions.JedisException.class, () -> first.get("record"));
            assertThrows(RuntimeException.class, () -> first.getAndTouch("record", Duration.ofSeconds(1)));
            assertThrows(RuntimeException.class, () -> first.touch("record", Duration.ofSeconds(1)));
         }
      }
   }
}
