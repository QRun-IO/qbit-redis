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

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qbits.redis.RedisRuntimeService;
import com.kingsrook.qbits.redis.RedisQBitConfig;

/*******************************************************************************
 ** Public access to application-owned Redis connections for collaborating qbits.
 *******************************************************************************/
public final class RedisAccess
{
   /***************************************************************************
    ** Static access only; runtime lifecycle remains owned by the Redis qbit.
    ***************************************************************************/
   private RedisAccess()
   {
   }

   /***************************************************************************
    ** Access script execution without exposing the shared client or pool.
    ** Throws if the named connection is not started; never creates a fallback pool.
    ***************************************************************************/
   public static RedisScriptExecutor scripts(QInstance instance, String name)
   {
      return (RedisRuntimeService.connection(instance, name).scripts());
   }
   /***************************************************************************
    ** Create a bounded cache using an already-started application connection.
    ***************************************************************************/
   public static RedisCache cache(QInstance instance, String name, RedisNamespace namespace, int maximumValueBytes)
   {
      return (new RedisCache(RedisRuntimeService.connection(instance, name).client(), namespace, maximumValueBytes));
   }
   /***************************************************************************
    ** Inspect metadata before startup without opening a connection or pool.
    ***************************************************************************/
   public static boolean isConfigured(QInstance instance, String name)
   {
      return (instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast)
         .anyMatch(config -> config.getConnections().containsKey(name)));
   }
}
