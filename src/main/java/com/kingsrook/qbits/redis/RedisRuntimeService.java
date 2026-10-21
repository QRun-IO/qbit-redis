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

import com.kingsrook.qbits.redis.api.RedisTlsProvider;

import com.kingsrook.qbits.redis.api.RedisCredentialsProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javax.net.ssl.SSLParameters;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QRuntimeServiceInterface;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;

/*******************************************************************************
 ** Publish pools only after successful startup; close them after all services stop.
 *******************************************************************************/
public class RedisRuntimeService implements QRuntimeServiceInterface
{
   private static final Map<QInstance, Map<String, RedisConnection>> CONNECTIONS = new WeakHashMap<>();
   private QInstance instance;
   private Map<String, RedisConnection> owned;
   private boolean ownsRealtime;

   /***************************************************************************
    ** Runtime service identity.
    ***************************************************************************/
   @Override
   public String getName()
   {
      return ("redis");
   }

   /***************************************************************************
    ** Resolve only started connections for the specified application instance.
    ***************************************************************************/
   public static RedisConnection connection(QInstance instance, String name)
   {
      synchronized(CONNECTIONS)
      {
         Map<String, RedisConnection> connections = CONNECTIONS.get(instance);
         if(connections == null || !connections.containsKey(name))
         {
            throw (new IllegalStateException("Named Redis connection is not started"));
         }
         return (connections.get(name));
      }
   }

   /***************************************************************************
    ** Snapshot references before health checks so no network call holds the lock.
    ***************************************************************************/
   static Map<String, RedisConnection> connections(QInstance instance)
   {
      synchronized(CONNECTIONS)
      {
         return (CONNECTIONS.getOrDefault(instance, Map.of()));
      }
   }

   /***************************************************************************
    ** Validate and authenticate every pool; unwind partial startup on any failure.
    ***************************************************************************/
   @Override
   public synchronized void start(QInstance instance) throws QException
   {
      if(owned != null)
      {
         throw (new QException("Redis runtime is already started"));
      }
      RedisQBitConfig config = instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast).findFirst()
         .orElseThrow(() -> new QException("Redis QBit is not installed"));
      var errors = new ArrayList<String>();
      config.validate(instance, errors);
      if(!errors.isEmpty())
      {
         throw (new QException("Invalid Redis runtime configuration"));
      }
      Map<String, RedisConnection> pending = new LinkedHashMap<>();
      try
      {
         QContext.withTemporaryContext(new CapturedContext(instance, null), () ->
         {
            for(var entry : config.getConnections().entrySet())
            {
               var settings = entry.getValue();
               SSLParameters tls = new SSLParameters();
               tls.setEndpointIdentificationAlgorithm("HTTPS");
               var client = DefaultJedisClientConfig.builder().ssl(settings.getTls()).sslParameters(tls)
                  .connectionTimeoutMillis(settings.getConnectionTimeoutMillis()).socketTimeoutMillis(settings.getSocketTimeoutMillis());
               if(entry.getKey().equals(config.getRealtimeConnection()))
               {
                  client.blockingSocketTimeoutMillis(RedisRealtimeBus.SUBSCRIPTION_TIMEOUT_MILLIS);
               }
               if(settings.getTlsProvider() != null)
               {
                  var provider = QCodeLoader.getAdHoc(RedisTlsProvider.class, settings.getTlsProvider());
                  client.sslSocketFactory(java.util.Objects.requireNonNull(provider.getContext()).getSocketFactory());
               }
               if(settings.getCredentialsProvider() != null)
               {
                  var credentials = QCodeLoader.getAdHoc(RedisCredentialsProvider.class, settings.getCredentialsProvider());
                  client.credentialsProvider(() -> resolveCredentials(instance, credentials));
               }
               var connection = new RedisConnection(new HostAndPort(settings.getHost(), settings.getPort()), client.build(),
                  settings.getMaximumConnections(), Duration.ofMillis(settings.getMaximumWaitMillis()));
               pending.put(entry.getKey(), connection);
               connection.client().ping();
            }
         });
         synchronized(CONNECTIONS)
         {
            if(CONNECTIONS.containsKey(instance))
            {
               throw (new QException("Redis runtime is already started for this instance"));
            }
            CONNECTIONS.put(instance, Map.copyOf(pending));
         }
         this.instance = instance;
         owned = pending;
         ownsRealtime = config.getRealtimeTopic() != null;
      }
      catch(Exception failure)
      {
         closeAll(pending);
         // Providers may throw exceptions containing secrets. Do not retain their messages or causes.
         throw (new QException("Redis startup failed (" + failure.getClass().getSimpleName() + "); check connectivity, TLS and credentials"));
      }
   }

   /***************************************************************************
    ** Keep shared connections available while dependent services finish stopping.
    ***************************************************************************/
   @Override
   public void stop()
   {
   }

   /***************************************************************************
    ** Remove the registry entry and close every pool, even after partial failures.
    ***************************************************************************/
   @Override
   public synchronized void afterApplicationStop()
   {
      if(owned != null)
      {
         if(ownsRealtime)
         {
            com.kingsrook.qqq.backend.core.realtime.RealtimeBus.close(instance);
         }
         synchronized(CONNECTIONS)
         {
            CONNECTIONS.remove(instance);
         }
         closeAll(owned);
         owned = null;
         instance = null;
         ownsRealtime = false;
      }
   }

   /***************************************************************************
    ** A close failure must not leave later pools unclosed or expose credentials.
    ***************************************************************************/
   private static void closeAll(Map<String, RedisConnection> connections)
   {
      for(RedisConnection connection : connections.values())
      {
         try
         {
            connection.close();
         }
         catch(RuntimeException failure)
         {
            QLogger.getLogger(RedisRuntimeService.class).warn("Could not close a Redis connection pool");
         }
      }
   }

   /***************************************************************************
    ** Jedis closes a just-opened socket on JedisException, not arbitrary provider
    ** exceptions. Snapshot the provider result and normalize failures at this boundary.
    ***************************************************************************/
   private static redis.clients.jedis.RedisCredentials resolveCredentials(QInstance instance, RedisCredentialsProvider provider)
   {
      try
      {
         var result = new java.util.concurrent.atomic.AtomicReference<redis.clients.jedis.RedisCredentials>();
         QContext.withTemporaryContext(new CapturedContext(instance, null), () ->
         {
            var credentials = java.util.Objects.requireNonNull(provider.getCredentials());
            char[] password = java.util.Objects.requireNonNull(credentials.getPassword());
            result.set(new redis.clients.jedis.DefaultRedisCredentials(credentials.getUser(), password.clone()));
         });
         return (result.get());
      }
      catch(RuntimeException failure)
      {
         throw (new redis.clients.jedis.exceptions.JedisConnectionException("Redis credentials could not be resolved"));
      }
   }
}
