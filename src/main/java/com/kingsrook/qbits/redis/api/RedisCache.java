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


package com.kingsrook.qbits.redis.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.GetExParams;
import redis.clients.jedis.params.SetParams;

/*******************************************************************************
 ** Bounded shared byte cache. Outages propagate; they are never reported as misses.
 *******************************************************************************/
public final class RedisCache
{
   private final UnifiedJedis client;
   private final RedisNamespace namespace;
   private final int maximumValueBytes;

   /***************************************************************************
    ** The host owns the pooled client; a cache never closes a shared connection.
    ***************************************************************************/
   public RedisCache(UnifiedJedis client, RedisNamespace namespace, int maximumValueBytes)
   {
      this.client = Objects.requireNonNull(client);
      this.namespace = Objects.requireNonNull(namespace);
      if(maximumValueBytes < 1)
      {
         throw (new IllegalArgumentException("Cache value limit must be positive"));
      }
      this.maximumValueBytes = maximumValueBytes;
   }

   /***************************************************************************
    ** One atomic SET applies the value and expiration together.
    ***************************************************************************/
   public void put(String key, byte[] value, Duration ttl)
   {
      Objects.requireNonNull(value);
      if(value.length > maximumValueBytes)
      {
         throw (new IllegalArgumentException("Cache value exceeds its byte limit"));
      }
      client.set(bytes(key), value, SetParams.setParams().px(milliseconds(ttl)));
   }

   /***************************************************************************
    ** A missing or expired key is empty; connection failures remain exceptions.
    ***************************************************************************/
   public Optional<byte[]> get(String key)
   {
      return (Optional.ofNullable(client.get(bytes(key))));
   }

   /***************************************************************************
    ** Atomically read and renew an existing value. Requires Redis 6.2 or later.
    ** No fallback or retry: transport errors may follow a completed renewal.
    ***************************************************************************/
   public Optional<byte[]> getAndTouch(String key, Duration ttl)
   {
      return (Optional.ofNullable(client.getEx(bytes(key), GetExParams.getExParams().px(milliseconds(ttl)))));
   }

   /***************************************************************************
    ** Renew only an existing key; an expired or removed entry stays absent.
    ***************************************************************************/
   public boolean touch(String key, Duration ttl)
   {
      return (client.pexpire(bytes(key), milliseconds(ttl)) > 0);
   }

   /***************************************************************************
    ** Reject zero, negative and sub-millisecond expiration before issuing a command.
    ***************************************************************************/
   private static long milliseconds(Duration ttl)
   {
      long value = Objects.requireNonNull(ttl).toMillis();
      if(value <= 0)
      {
         throw (new IllegalArgumentException("Cache TTL must be at least one millisecond"));
      }
      return (value);
   }

   /***************************************************************************
    ** Remove exactly one key in this namespace without scans or wildcard deletes.
    ***************************************************************************/
   public boolean remove(String key)
   {
      return (client.del(bytes(key)) > 0);
   }

   /***************************************************************************
    ** Use a deterministic encoding for the binary Redis API.
    ***************************************************************************/
   private byte[] bytes(String key)
   {
      return (namespace.key(key).getBytes(StandardCharsets.UTF_8));
   }
}
