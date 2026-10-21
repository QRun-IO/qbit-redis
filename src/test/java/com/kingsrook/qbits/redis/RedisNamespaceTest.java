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

import com.kingsrook.qbits.redis.api.RedisNamespace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Delimiter injection, Unicode and tenant names cannot collide across namespaces.
 *******************************************************************************/
class RedisNamespaceTest
{
   /***************************************************************************
    ** Redis keys must not inherit user-supplied separators or cluster hash tags.
    ***************************************************************************/
   @Test
   void scopesDoNotCollide()
   {
      String first = new RedisNamespace("prod", "app", "tenant:a", "cache").key("{record}:1");
      String second = new RedisNamespace("prod", "app", "tenant", "a:cache").key("{record}:1");
      assertNotEquals(first, second);
      assertFalse(first.contains("{"));
      assertNotEquals(first, new RedisNamespace("prod", "app", "tenant:b", "cache").key("{record}:1"));
      assertEquals(first, new RedisNamespace("prod", "app", "tenant:a", "cache").key("{record}:1"));
   }

   /***************************************************************************
    ** Missing scope and multibyte values exceeding the byte budget are rejected.
    ***************************************************************************/
   @Test
   void scopeAndKeyBounds()
   {
      assertThrows(IllegalArgumentException.class, () -> new RedisNamespace("prod", "app", "", "cache"));
      assertThrows(IllegalArgumentException.class, () -> new RedisNamespace("prod", "app", "é".repeat(129), "cache"));
      RedisNamespace namespace = new RedisNamespace("prod", "app", "tenant", "cache");
      assertThrows(IllegalArgumentException.class, () -> namespace.key("x".repeat(1025)));
      assertNotEquals(namespace.key("é"), namespace.key("e"));
   }
}
