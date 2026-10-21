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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;

/*******************************************************************************
 ** Register reusable Redis infrastructure without adding application tables.
 *******************************************************************************/
public class RedisQBitProducer implements QBitMetaDataProducer<RedisQBitConfig>
{
   private RedisQBitConfig config;

   /***************************************************************************
    ** Set named connection configuration.
    ***************************************************************************/
   public RedisQBitProducer withQBitConfig(RedisQBitConfig value)
   {
      config = value;
      return (this);
   }

   /***************************************************************************
    ** Configuration attached to the produced metadata.
    ***************************************************************************/
   @Override
   public RedisQBitConfig getQBitConfig()
   {
      return (config);
   }

   /***************************************************************************
    ** Stable infrastructure namespace.
    ***************************************************************************/
   @Override
   public String getNamespace()
   {
      return ("redis");
   }

   /***************************************************************************
    ** Use the actual packaged artifact version.
    ***************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      try(InputStream stream = getClass().getResourceAsStream("/qbit-redis.properties"))
      {
         if(stream == null)
         {
            throw (new IllegalStateException("Missing Redis artifact version resource"));
         }
         Properties properties = new Properties();
         properties.load(stream);
         String version = properties.getProperty("version");
         if(version == null || version.isBlank() || version.contains("${"))
         {
            throw (new IllegalStateException("Redis artifact version is not filtered"));
         }
         return (new QBitMetaData().withGroupId("com.kingsrook.qbits").withArtifactId("qbit-redis")
            .withVersion(version).withNamespace(getNamespace()).withConfig(config));
      }
      catch(java.io.IOException e)
      {
         throw (new IllegalStateException("Cannot read Redis artifact version", e));
      }
   }

   /***************************************************************************
    ** Validate before mutation and reject duplicate installation.
    ***************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance instance) throws QException
   {
      if(config == null)
      {
         throw (new QException("RedisQBitConfig is required"));
      }
      var errors = new ArrayList<String>();
      config.validate(instance, errors);
      if(!errors.isEmpty())
      {
         throw (new QBitConfigValidationException(config, errors));
      }
      if(instance.getQBits() != null && instance.getQBits().values().stream().anyMatch(bit -> bit.getConfig() instanceof RedisQBitConfig))
      {
         throw (new QException("Redis QBit is already installed"));
      }
      if(instance.getApp("redis") != null || instance.getWidget("redisConnections") != null || instance.getWidget("redisRealtime") != null
         || instance.getWidget("redisHealthHistory") != null)
      {
         throw (new QException("Redis admin metadata conflicts with existing application metadata"));
      }
      var health = instance.getSupplementalMetaData(com.kingsrook.qqq.backend.core.health.HealthChecksMetaData.NAME);
      if(health instanceof com.kingsrook.qqq.backend.core.health.HealthChecksMetaData checks
         && checks.getChecks().stream().anyMatch(check -> "redis.connections".equals(check.getName())))
      {
         throw (new QException("Redis health check conflicts with an existing check"));
      }
      if(config.getRealtimeTopic() != null)
      {
         var realtime = com.kingsrook.qqq.backend.core.realtime.RealtimeMetaData.of(instance);
         if(realtime.getBus() != null)
         {
            throw (new QException("A realtime bus is already configured"));
         }
         instance.withSupplementalMetaData(realtime.withBus(new QCodeReference(RedisRealtimeBus.class)));
      }
      instance.addQBit(getQBitMetaData());
      RedisAdminWidgets.define(instance);
      com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles.register(instance, "help/qbit-redis.yaml");
      instance.withRuntimeService(new QCodeReference(RedisRuntimeService.class));
      if(config.getRealtimeTopic() != null)
      {
         instance.withRuntimeService(new QCodeReference(RedisRealtimeRuntimeService.class));
      }
      com.kingsrook.qqq.backend.core.health.HealthChecksMetaData.ofOrWithNew(instance).withCheck(
         new com.kingsrook.qqq.backend.core.health.HealthCheckMetaData().withName("redis.connections").withLabel("Redis connections")
            .withComponent("redis").withKind(com.kingsrook.qqq.backend.core.health.HealthCheckKind.DEPENDENCY)
            .withCategory(com.kingsrook.qqq.backend.core.health.HealthCategory.CORE).withIntervalSeconds(30)
            .withCheck(new QCodeReference(RedisHealthCheck.class)));
      return (new MetaDataProducerMultiOutput());
   }
}
