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

import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthDetails;

/*******************************************************************************
 ** Observe connectivity without returning keys, credentials or provider exceptions.
 *******************************************************************************/
public class RedisHealthCheck implements HealthCheck
{
   /***************************************************************************
    ** Ping with the configured bounded pool/socket timeouts and report utilization.
    ***************************************************************************/
   @Override
   public HealthCheckOutcome run(HealthCheckInput input)
   {
      var connections = RedisRuntimeService.connections(input.getQInstance());
      if(connections.isEmpty())
      {
         return (HealthCheckOutcome.unknown("Redis runtime is not started"));
      }
      int active = 0;
      int idle = 0;
      int waiting = 0;
      try
      {
         for(var connection : connections.values())
         {
            if(input.getDeadline() != null && !java.time.Instant.now().isBefore(input.getDeadline()))
            {
               return (HealthCheckOutcome.unknown("Redis health deadline exceeded"));
            }
            connection.client().ping();
            var metrics = connection.metrics();
            active += metrics.active();
            idle += metrics.idle();
            waiting += metrics.waiting();
         }
         var details = new HealthDetails().withValue("connections", connections.size()).withValue("active", active)
            .withValue("idle", idle).withValue("waiting", waiting);
         var config = input.getQInstance().getQBits().values().stream().map(bit -> bit.getConfig())
            .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast).findFirst().orElseThrow();
         if(config.getRealtimeTopic() != null)
         {
            var bus = com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(input.getQInstance()).orElse(null);
            if(!(bus instanceof RedisRealtimeBus redis) || !redis.isListening())
            {
               return (HealthCheckOutcome.down("Redis realtime subscription is unavailable").withDetails(details));
            }
            var metrics = redis.metrics();
            details.withValue("realtimeQueued", metrics.queued()).withValue("realtimeDropped", metrics.dropped())
               .withValue("realtimePublishFailures", metrics.publishFailures()).withValue("realtimeReconnects", metrics.reconnects());
         }
         return (HealthCheckOutcome.up("Redis connections and configured transport are reachable").withDetails(details));
      }
      catch(RuntimeException failure)
      {
         return (HealthCheckOutcome.down("A Redis connection is unavailable"));
      }
   }
}
