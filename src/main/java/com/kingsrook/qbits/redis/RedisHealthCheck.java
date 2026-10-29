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
 ** Observe configured connectivity/write checks without exposing keys or credentials.
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
      int writeChecks = 0;
      try
      {
         var config = input.getQInstance().getQBits().values().stream().map(bit -> bit.getConfig())
            .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast).findFirst().orElseThrow();
         for(var entry : connections.entrySet())
         {
            var connection = entry.getValue();
            if(input.getDeadline() != null && !java.time.Instant.now().isBefore(input.getDeadline()))
            {
               return (HealthCheckOutcome.unknown("Redis health deadline exceeded"));
            }
            connection.client().ping();
            String prefix = config.getConnections().get(entry.getKey()).getWriteHealthKeyPrefix();
            if(prefix != null)
            {
               if(input.getDeadline() != null && !java.time.Instant.now().isBefore(input.getDeadline()))
               {
                  return (HealthCheckOutcome.unknown("Redis health deadline exceeded"));
               }
               // NX cannot overwrite existing data; expiry also cleans up after a lost reply.
               String result = connection.client().set(prefix + java.util.UUID.randomUUID(), "1",
                  redis.clients.jedis.params.SetParams.setParams().nx().px(1000));
               if(!"OK".equals(result))
               {
                  return (HealthCheckOutcome.down("A Redis write health probe was not accepted"));
               }
               writeChecks++;
            }
            var metrics = connection.metrics();
            active += metrics.active();
            idle += metrics.idle();
            waiting += metrics.waiting();
         }
         var details = new HealthDetails().withValue("connections", connections.size()).withValue("active", active)
            .withValue("idle", idle).withValue("waiting", waiting).withValue("writeChecks", writeChecks);
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
         return (HealthCheckOutcome.up("Redis connections and configured checks succeeded").withDetails(details));
      }
      catch(RuntimeException failure)
      {
         return (HealthCheckOutcome.down("A Redis connection or configured probe is unavailable"));
      }
   }
}
