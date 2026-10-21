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

import java.util.List;

/*******************************************************************************
 ** Execute trusted domain scripts without exposing connection or pool ownership.
 *******************************************************************************/
@FunctionalInterface
public interface RedisScriptExecutor
{
   /***************************************************************************
    ** Return Redis values as Long, String, List, or null. Keys must share a cluster
    ** slot. Only a missing script is retried; other failures propagate as runtime
    ** exceptions and may follow applied writes. Callers must not blindly retry.
    ***************************************************************************/
   Object execute(RedisScript script, List<String> keys, List<String> arguments);
}
