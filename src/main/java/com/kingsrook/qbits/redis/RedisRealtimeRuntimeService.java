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

import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QRuntimeServiceInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;

/*******************************************************************************
 ** Start transport after Redis pools, so readiness does not wait for an HTTP client.
 *******************************************************************************/
public class RedisRealtimeRuntimeService implements QRuntimeServiceInterface
{
   private QInstance instance;

   /***************************************************************************
    ** Distinct lifecycle identity for the dependent realtime service.
    ***************************************************************************/
   @Override
   public String getName()
   {
      return ("redis-realtime");
   }

   /***************************************************************************
    ** The launcher starts this service after RedisRuntimeService.
    ***************************************************************************/
   @Override
   public synchronized void start(QInstance instance) throws QException
   {
      if(this.instance != null)
      {
         throw (new QException("Redis realtime service is already started"));
      }
      try
      {
         if(!(RealtimeBus.get(instance) instanceof RedisRealtimeBus))
         {
            throw (new QException("Configured realtime bus is not Redis"));
         }
         this.instance = instance;
      }
      catch(RuntimeException failure)
      {
         throw (new QException("Redis realtime startup failed"));
      }
   }

   /***************************************************************************
    ** Stop transport before the pool owner releases shared connections.
    ***************************************************************************/
   @Override
   public synchronized void stop()
   {
      if(instance != null)
      {
         RealtimeBus.close(instance);
         instance = null;
      }
   }
}
