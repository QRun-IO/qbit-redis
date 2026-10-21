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
import java.util.List;
import com.kingsrook.qbits.redis.api.RedisScript;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Real Redis proves script-cache recovery and avoids retrying applied writes.
 *******************************************************************************/
class RedisScriptTest
{
   /***************************************************************************
    ** A Lua error can follow a write. Only NOSCRIPT is safe to retry with source.
    ***************************************************************************/
   @Test
   void cacheRecoveryDoesNotRetryScriptErrors() throws Exception
   {
      assertThrows(NullPointerException.class, () -> new RedisScript(null));
      assertThrows(IllegalArgumentException.class, () -> new RedisScript(" "));
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var endpoint = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).build();
         try(var connection = new RedisConnection(endpoint, config, 2, Duration.ofMillis(500)))
         {
            var scripts = connection.scripts();
            var increment = new RedisScript("return redis.call('INCR', KEYS[1])");
            assertEquals(1L, scripts.execute(increment, List.of("counter"), List.of()));
            assertEquals(List.of(true), connection.client().scriptExists(List.of(increment.sha())));
            assertEquals(2L, scripts.execute(increment, List.of("counter"), List.of()));
            connection.client().scriptFlush();
            assertEquals(3L, scripts.execute(increment, List.of("counter"), List.of()));
            var failing = new RedisScript("redis.call('INCR', KEYS[1]); return redis.error_reply('intentional failure')");
            assertThrows(RuntimeException.class, () -> scripts.execute(failing, List.of("counter"), List.of()));
            assertEquals("4", connection.client().get("counter"));
            assertThrows(RuntimeException.class, () -> scripts.execute(failing, List.of("counter"), List.of()));
            assertEquals("5", connection.client().get("counter"));
            var values = new RedisScript("return {ARGV[1], 7, {ARGV[2]}}");
            assertEquals(List.of("one", 7L, List.of("two")), scripts.execute(values, List.of(), List.of("one", "two")));
            assertNull(scripts.execute(new RedisScript("return nil"), List.of(), List.of()));
            connection.close();
            assertThrows(RuntimeException.class, () -> scripts.execute(increment, List.of("counter"), List.of()));
         }
      }
   }
}
