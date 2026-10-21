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

import com.kingsrook.qbits.redis.api.RedisScriptExecutor;
import redis.clients.jedis.exceptions.JedisNoScriptException;
import java.time.Duration;
import java.util.Objects;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

/*******************************************************************************
 ** Own one bounded pool. Native client configuration supplies TLS and credentials.
 *******************************************************************************/
public final class RedisConnection implements AutoCloseable
{
   private final RedisClient client;

   /***************************************************************************
    ** Reject infinite network waits; do not log or retain credentials separately.
    ***************************************************************************/
   public RedisConnection(HostAndPort endpoint, JedisClientConfig config, int maximumConnections, Duration maximumWait)
   {
      Objects.requireNonNull(endpoint);
      Objects.requireNonNull(config);
      Objects.requireNonNull(maximumWait);
      if(maximumConnections < 1 || maximumWait.isNegative() || maximumWait.isZero()
         || config.getConnectionTimeoutMillis() <= 0 || config.getSocketTimeoutMillis() <= 0)
      {
         throw (new IllegalArgumentException("Redis pool size and timeouts must be positive"));
      }
      ConnectionPoolConfig pool = new ConnectionPoolConfig();
      pool.setMaxTotal(maximumConnections);
      pool.setMaxIdle(maximumConnections);
      pool.setMinIdle(0);
      pool.setBlockWhenExhausted(true);
      pool.setMaxWait(maximumWait);
      pool.setTestWhileIdle(true);
      client = RedisClient.builder().hostAndPort(endpoint).clientConfig(config).poolConfig(pool).build();
   }

   /***************************************************************************
    ** Native commands support atomic domain operations without reimplementing Redis.
    ***************************************************************************/
   public RedisClient client()
   {
      return (client);
   }

   /***************************************************************************
    ** Keep driver-specific cache recovery within the owning Redis connection.
    ***************************************************************************/
   public RedisScriptExecutor scripts()
   {
      return ((script, keys, arguments) ->
      {
         try
         {
            return (client.evalsha(script.sha(), keys, arguments));
         }
         catch(JedisNoScriptException missing)
         {
            return (client.eval(script.source(), keys, arguments));
         }
      });
   }

   /***************************************************************************
    ** Pool utilization contains no endpoint, key, user or credential data.
    ***************************************************************************/
   public PoolMetrics metrics()
   {
      var pool = client.getPool();
      return (new PoolMetrics(pool.getNumActive(), pool.getNumIdle(), pool.getNumWaiters(), pool.getMaxTotal()));
   }

   /***************************************************************************
    ** Point-in-time counters for host metrics and health integrations.
    ***************************************************************************/
   public record PoolMetrics(int active, int idle, int waiting, int maximum)
   {
   }

   /***************************************************************************
    ** Close the owned pool during application shutdown.
    ***************************************************************************/
   @Override
   public void close()
   {
      client.close();
   }
}
