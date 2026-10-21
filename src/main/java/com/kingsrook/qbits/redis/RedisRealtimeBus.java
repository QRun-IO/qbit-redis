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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.InMemoryRealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBusInterface;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPubSub;

/*******************************************************************************
 ** Bounded best-effort hints. Reconnect and overflow require authorized rereads.
 *******************************************************************************/
public final class RedisRealtimeBus implements RealtimeBusInterface, AutoCloseable
{
   private static final int MAX_BYTES = 65536;
   static final int SUBSCRIPTION_TIMEOUT_MILLIS = 15000;
   private static final int HEARTBEAT_MILLIS = 5000;
   private final QInstance instance;
   private final String connectionName;
   private final String topic;
   private final String heartbeatTopic;
   private final InMemoryRealtimeBus local = new InMemoryRealtimeBus();
   private final ArrayBlockingQueue<RealtimeMessage> pending = new ArrayBlockingQueue<>(256);
   private final AtomicBoolean resync = new AtomicBoolean();
   private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
   private final java.util.concurrent.atomic.AtomicLong publishFailures = new java.util.concurrent.atomic.AtomicLong();
   private final java.util.concurrent.atomic.AtomicLong subscriptions = new java.util.concurrent.atomic.AtomicLong();
   private final Thread reader;
   private final Thread dispatcher;
   private final Thread heartbeat;
   private final java.util.concurrent.atomic.AtomicLong heartbeats = new java.util.concurrent.atomic.AtomicLong();
   private volatile Connection subscription;
   private volatile boolean closed;
   private volatile boolean listening;

   /***************************************************************************
    ** QCodeLoader entry point; runtime pools may start after metadata consumers.
    ***************************************************************************/
   public RedisRealtimeBus()
   {
      this(QContext.getQInstance());
   }

   /***************************************************************************
    ** One reserved subscription connection per node; no socket writes in callbacks.
    ***************************************************************************/
   public RedisRealtimeBus(QInstance instance)
   {
      this.instance = instance;
      var config = instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(RedisQBitConfig.class::isInstance).map(RedisQBitConfig.class::cast).findFirst().orElseThrow();
      connectionName = java.util.Objects.requireNonNull(config.getRealtimeConnection());
      topic = java.util.Objects.requireNonNull(config.getRealtimeTopic());
      heartbeatTopic = topic + ":heartbeat:" + java.util.UUID.randomUUID();
      dispatcher = Thread.ofVirtual().name("redis-realtime-dispatch").start(this::dispatch);
      reader = Thread.ofVirtual().name("redis-realtime-subscribe").start(this::listen);
      heartbeat = Thread.ofVirtual().name("redis-realtime-heartbeat").start(this::heartbeat);
   }

   /***************************************************************************
    ** Failed hints never fail record writes; local clients reconcile independently.
    ***************************************************************************/
   @Override
   public void publish(RealtimeMessage message)
   {
      if(closed)
      {
         return;
      }
      try
      {
         String json = JsonUtils.toJson(message);
         if(json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || !valid(message))
         {
            dropped.incrementAndGet();
            resync.set(true);
            return;
         }
         RedisRuntimeService.connection(instance, connectionName).client().publish(topic, json);
      }
      catch(Exception failure)
      {
         publishFailures.incrementAndGet();
         resync.set(true);
      }
   }

   /***************************************************************************
    ** Subscribe locally; the Redis echo is the only delivery path, avoiding duplicates.
    ***************************************************************************/
   @Override
   public AutoCloseable subscribe(Consumer<RealtimeMessage> listener)
   {
      return (local.subscribe(listener));
   }

   /***************************************************************************
    ** Report acknowledged subscription readiness, not merely a connected command pool.
    ***************************************************************************/
   public boolean isListening()
   {
      return (listening && !closed);
   }

   /***************************************************************************
    ** Bounded transport metrics contain no topics, tenant identifiers or payloads.
    ***************************************************************************/
   public TransportMetrics metrics()
   {
      return (new TransportMetrics(isListening(), pending.size(), dropped.get(), publishFailures.get(), Math.max(0, subscriptions.get() - 1), heartbeats.get()));
   }

   /***************************************************************************
    ** Queue pressure and reconnect counters for host observability.
    ***************************************************************************/
   public record TransportMetrics(boolean listening, int queued, long dropped, long publishFailures, long reconnects, long heartbeats)
   {
   }

   /***************************************************************************
    ** Retry with capped delay; callbacks only parse and enqueue bounded messages.
    ***************************************************************************/
   private void listen()
   {
      long delay = 100;
      while(!closed)
      {
         try(Connection connection = RedisRuntimeService.connection(instance, connectionName).client().getPool().getResource())
         {
            subscription = connection;
            if(closed)
            {
               break;
            }
            new JedisPubSub()
            {
               /***************************************************************
                ** Acknowledgement, rather than socket creation, marks readiness.
                ***************************************************************/
               @Override
               public void onSubscribe(String channel, int count)
               {
                  if(closed)
                  {
                     connection.setBroken();
                     try
                     {
                        connection.forceDisconnect();
                     }
                     catch(java.io.IOException ignored)
                     {
                     }
                     return;
                  }
                  if(count == 2)
                  {
                     listening = true;
                     subscriptions.incrementAndGet();
                     resync.set(true);
                  }
               }

               /***************************************************************
                ** Keep the network reader independent from slow local consumers.
                ***************************************************************/
               @Override
               public void onMessage(String channel, String payload)
               {
                  if(heartbeatTopic.equals(channel))
                  {
                     heartbeats.incrementAndGet();
                     return;
                  }
                  if(closed || payload.length() > MAX_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                  {
                     dropped.incrementAndGet();
                     resync.set(true);
                     return;
                  }
                  try
                  {
                     var message = JsonUtils.toObject(payload, RealtimeMessage.class);
                     if(!valid(message) || !pending.offer(message))
                     {
                        dropped.incrementAndGet();
                        resync.set(true);
                     }
                  }
                  catch(Exception failure)
                  {
                     dropped.incrementAndGet();
                     resync.set(true);
                  }
               }
            }.proceed(connection, topic, heartbeatTopic);
         }
         catch(Exception failure)
         {
            resync.set(true);
         }
         finally
         {
            subscription = null;
            if(listening)
            {
               delay = 100;
            }
            listening = false;
         }
         if(!closed)
         {
            try
            {
               Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, delay / 2), delay + 1));
               delay = Math.min(5000, delay * 2);
            }
            catch(InterruptedException interrupted)
            {
               Thread.currentThread().interrupt();
               return;
            }
         }
      }
   }

   /***************************************************************************
    ** Private per-node pulses avoid quadratic fanout and keep idle reads bounded.
    ***************************************************************************/
   private void heartbeat()
   {
      while(!closed)
      {
         try
         {
            if(listening)
            {
               RedisRuntimeService.connection(instance, connectionName).client().publish(heartbeatTopic, "pulse");
            }
         }
         catch(RuntimeException failure)
         {
            publishFailures.incrementAndGet();
            resync.set(true);
         }
         try
         {
            Thread.sleep(HEARTBEAT_MILLIS);
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
            return;
         }
      }
   }

   /***************************************************************************
    ** A slow hub cannot grow the subscription reader's memory without limit.
    ***************************************************************************/
   private void dispatch()
   {
      while(!closed)
      {
         try
         {
            if(resync.getAndSet(false))
            {
               local.publish(new RealtimeMessage().withChannel("instance").withEvent("resync"));
            }
            var message = pending.poll(250, TimeUnit.MILLISECONDS);
            if(message != null)
            {
               local.publish(message);
            }
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
            return;
         }
      }
   }

   /***************************************************************************
    ** Reject malformed envelopes before they reach core channel handling.
    ***************************************************************************/
   private static boolean valid(RealtimeMessage message)
   {
      return (message != null && message.getChannel() != null && !message.getChannel().isBlank() && message.getChannel().length() <= 2048
         && message.getEvent() != null && !message.getEvent().isBlank() && message.getEvent().length() <= 128 && message.getData() != null
         && (message.getAudience() == null || message.getAudience().length() <= 2048));
   }

   /***************************************************************************
    ** Break a blocking subscription read and return its connection as unusable.
    ***************************************************************************/
   @Override
   public void close()
   {
      closed = true;
      listening = false;
      Connection current = subscription;
      if(current != null)
      {
         current.setBroken();
         try
         {
            current.forceDisconnect();
         }
         catch(java.io.IOException ignored)
         {
         }
      }
      reader.interrupt();
      dispatcher.interrupt();
      heartbeat.interrupt();
      pending.clear();
      local.close();
   }
}
