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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/*******************************************************************************
 ** Immutable trusted application script; never construct from end-user input.
 *******************************************************************************/
public final class RedisScript
{
   private final String source;
   private final String sha;

   /***************************************************************************
    ** Compute the Redis script-cache identifier once, independently of the driver.
    ***************************************************************************/
   public RedisScript(String source)
   {
      this.source = Objects.requireNonNull(source);
      if(source.isBlank())
      {
         throw (new IllegalArgumentException("Redis script must not be blank"));
      }
      try
      {
         sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8)));
      }
      catch(NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("Required digest algorithm unavailable", e));
      }
   }

   /***************************************************************************
    ** Trusted Lua source used when Redis has evicted its cached script.
    ***************************************************************************/
   public String source()
   {
      return (source);
   }

   /***************************************************************************
    ** Redis protocol SHA-1 identifier, not a security signature.
    ***************************************************************************/
   public String sha()
   {
      return (sha);
   }
}
