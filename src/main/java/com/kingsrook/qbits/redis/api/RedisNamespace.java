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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/*******************************************************************************
 ** Collision-safe keys scoped by deployment, application, tenant and feature.
 *******************************************************************************/
public final class RedisNamespace
{
   private final String prefix;

   /***************************************************************************
    ** Require explicit scope, including a host-defined tenant for shared state.
    ***************************************************************************/
   public RedisNamespace(String deployment, String application, String tenant, String feature)
   {
      prefix = "qqq:v1:" + segment(deployment, 256) + ":" + segment(application, 256)
         + ":" + segment(tenant, 256) + ":" + segment(feature, 256) + ":";
   }

   /***************************************************************************
    ** Encode logical keys so separators and Redis hash tags cannot alter scope.
    ***************************************************************************/
   public String key(String logicalKey)
   {
      return (prefix + segment(logicalKey, 1024));
   }

   /***************************************************************************
    ** Bound UTF-8 bytes rather than UTF-16 characters.
    ***************************************************************************/
   private static String segment(String value, int maximumBytes)
   {
      if(value == null || value.isBlank())
      {
         throw (new IllegalArgumentException("Redis key components must be nonblank"));
      }
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if(bytes.length > maximumBytes)
      {
         throw (new IllegalArgumentException("Redis key component exceeds its byte limit"));
      }
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
   }
}
