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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;

/*******************************************************************************
 ** Named connections let cache, presence and session workloads use separate pools.
 *******************************************************************************/
public class RedisQBitConfig implements QBitConfig
{
   private final Map<String, RedisConnectionConfig> connections = new LinkedHashMap<>();
   private String realtimeConnection;
   private String realtimeTopic;

   /***************************************************************************
    ** Opt into a deployment/application-scoped realtime bus on a dedicated pool.
    ***************************************************************************/
   public RedisQBitConfig withRealtime(String connection, String deployment, String application)
   {
      String topic = new com.kingsrook.qbits.redis.api.RedisNamespace(deployment, application, "transport", "realtime-v1").key("hints");
      realtimeConnection = connection;
      realtimeTopic = topic;
      return (this);
   }

   /***************************************************************************
    ** Named pool used by the realtime transport.
    ***************************************************************************/
   public String getRealtimeConnection()
   {
      return (realtimeConnection);
   }

   /***************************************************************************
    ** Encoded topic excludes other deployments and applications.
    ***************************************************************************/
   public String getRealtimeTopic()
   {
      return (realtimeTopic);
   }

   /***************************************************************************
    ** Add a unique name; accidental replacement is a configuration error.
    ***************************************************************************/
   public RedisQBitConfig withConnection(String name, RedisConnectionConfig config)
   {
      if(name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}") || config == null || connections.containsKey(name))
      {
         throw (new IllegalArgumentException("Redis connections require unique valid names and nonnull configuration"));
      }
      connections.put(name, config);
      return (this);
   }

   /***************************************************************************
    ** Expose configuration without permitting callers to mutate the registry.
    ***************************************************************************/
   public Map<String, RedisConnectionConfig> getConnections()
   {
      return (Collections.unmodifiableMap(connections));
   }

   /***************************************************************************
    ** Invalid configuration must fail before runtime services are registered.
    ***************************************************************************/
   @Override
   public void validate(QInstance instance, List<String> errors)
   {
      if(connections.isEmpty())
      {
         errors.add("At least one named Redis connection is required");
      }
      connections.values().forEach(connection -> connection.validate(errors));
      if(realtimeTopic != null && (realtimeConnection == null || !connections.containsKey(realtimeConnection)
         || connections.get(realtimeConnection).getMaximumConnections() == null
         || connections.get(realtimeConnection).getMaximumConnections() < 2))
      {
         errors.add("Redis realtime requires a named pool with at least two connections");
      }
   }
}
