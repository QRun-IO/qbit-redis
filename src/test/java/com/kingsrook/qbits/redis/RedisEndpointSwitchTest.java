/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026. Kingsrook, LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.kingsrook.qbits.redis;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.exceptions.JedisConnectionException;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** A stable external endpoint changes target while existing QQQ clients survive.
 *******************************************************************************/
class RedisEndpointSwitchTest
{
   /***************************************************************************
    ** Fence the old process, promote replicated state and reroute existing sockets.
    ***************************************************************************/
   @Test
   void existingPoolsAndSubscriptionsRecoverAfterEndpointSwitch() throws Exception
   {
      try(var network = Network.newNetwork();
          var primary = new GenericContainer<>("redis:7.4-alpine").withNetwork(network).withNetworkAliases("primary").withExposedPorts(6379);
          var replica = new GenericContainer<>("redis:7.4-alpine").withNetwork(network).withExposedPorts(6379)
             .withCommand("redis-server", "--replicaof", "primary", "6379"))
      {
         primary.start();
         replica.start();
         try(var relay = new Relay(new HostAndPort(primary.getHost(), primary.getMappedPort(6379))))
         {
            var firstHost = host(relay.port());
            var secondHost = host(relay.port());
            var firstRuntime = new RedisRuntimeService();
            var secondRuntime = new RedisRuntimeService();
            try
            {
               firstRuntime.start(firstHost);
               secondRuntime.start(secondHost);
               var firstPool = RedisRuntimeService.connection(firstHost, "realtime");
               var secondPool = RedisRuntimeService.connection(secondHost, "realtime");
               assertEquals("OK", firstPool.client().set("endpoint-marker", "replicated"));
               assertEquals("replicated", secondPool.client().get("endpoint-marker"));
               assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
               {
                  while(!replica.execInContainer("redis-cli", "GET", "endpoint-marker").getStdout().trim().equals("replicated"))
                  {
                     Thread.sleep(25);
                  }
               });
               var events = new LinkedBlockingQueue<RealtimeMessage>();
               try(var firstBus = new RedisRealtimeBus(firstHost); var secondBus = new RedisRealtimeBus(secondHost))
               {
                  secondBus.subscribe(events::add);
                  assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                  {
                     while(!firstBus.isListening() || !secondBus.isListening())
                     {
                        Thread.sleep(25);
                     }
                  });
                  assertEquals("replicated", firstPool.client().get("endpoint-marker"));
                  assertEquals("replicated", secondPool.client().get("endpoint-marker"));
                  assertTrue(relay.socketCount() >= 8, "Two command and two subscription TCP pairs must exist before switching");
                  primary.stop();
                  long started = System.nanoTime();
                  var promotion = replica.execInContainer("redis-cli", "REPLICAOF", "NO", "ONE");
                  assertEquals(0, promotion.getExitCode());
                  assertEquals("OK", promotion.getStdout().trim());
                  relay.switchTo(new HostAndPort(replica.getHost(), replica.getMappedPort(6379)));
                  assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                  {
                     for(var pool : List.of(firstPool, secondPool))
                     {
                        while(true)
                        {
                           try
                           {
                              assertEquals("replicated", pool.client().get("endpoint-marker"));
                              break;
                           }
                           catch(JedisConnectionException expectedDuringSwitch)
                           {
                              Thread.sleep(25);
                           }
                        }
                     }
                     while(!firstBus.isListening() || !secondBus.isListening()
                        || firstBus.metrics().reconnects() == 0 || secondBus.metrics().reconnects() == 0)
                     {
                        Thread.sleep(25);
                     }
                  });
                  assertEquals("OK", firstPool.client().set("endpoint-marker", "after-switch"));
                  assertEquals("after-switch", secondPool.client().get("endpoint-marker"));
                  assertEquals("after-switch", replica.execInContainer("redis-cli", "GET", "endpoint-marker").getStdout().trim());
                  events.clear();
                  firstBus.publish(new RealtimeMessage().withChannel("table:document").withEvent("endpoint-switched"));
                  assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                  {
                     while(true)
                     {
                        var event = events.poll(100, TimeUnit.MILLISECONDS);
                        if(event != null && "endpoint-switched".equals(event.getEvent()))
                        {
                           break;
                        }
                     }
                  });
                  System.out.println("ENDPOINT_SWITCH_RECOVERY_MS " + Duration.ofNanos(System.nanoTime() - started).toMillis());
                  for(var pool : List.of(firstPool, secondPool))
                  {
                     assertEquals(2, pool.metrics().maximum());
                     assertTrue(pool.metrics().active() <= 2);
                     assertEquals(0, pool.metrics().waiting());
                  }
               }
               assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
               {
                  while(firstPool.metrics().active() != 0 || secondPool.metrics().active() != 0)
                  {
                     Thread.sleep(25);
                  }
               });
            }
            finally
            {
               firstRuntime.afterApplicationStop();
               secondRuntime.afterApplicationStop();
            }
         }
      }
   }

   /***************************************************************************
    ** Independently configured instances share only the stable loopback endpoint.
    ***************************************************************************/
   private static QInstance host(int port) throws Exception
   {
      var instance = RedisRuntimeTest.hostInstance();
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("realtime",
         new RedisConnectionConfig().withHost("127.0.0.1").withPort(port).withTls(false)
            .withMaximumConnections(2).withConnectionTimeoutMillis(500).withSocketTimeoutMillis(1000))
         .withRealtime("realtime", "test", "endpoint-switch")).produce(instance);
      return (instance);
   }

   /***************************************************************************
    ** Owned test relay; switching closes existing TCP pairs instead of moving them.
    ***************************************************************************/
   private static class Relay implements AutoCloseable
   {
      private final ServerSocket listener;
      private final List<Socket> sockets = new ArrayList<>();
      private HostAndPort target;
      private final Thread acceptor;

      /************************************************************************
       ** Bind only loopback and start a disposable accept loop.
       ************************************************************************/
      Relay(HostAndPort target) throws IOException
      {
         this.target = target;
         listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
         acceptor = Thread.ofVirtual().start(() ->
         {
            while(!listener.isClosed())
            {
               try
               {
                  Socket downstream = listener.accept();
                  synchronized(this)
                  {
                     if(listener.isClosed())
                     {
                        downstream.close();
                        continue;
                     }
                     sockets.add(downstream);
                     Socket upstream = new Socket();
                     sockets.add(upstream);
                     upstream.connect(new java.net.InetSocketAddress(this.target.getHost(), this.target.getPort()), 500);
                     Thread.ofVirtual().start(() -> copy(downstream, upstream));
                     Thread.ofVirtual().start(() -> copy(upstream, downstream));
                  }
               }
               catch(IOException expectedOnDisconnect)
               {
                  // Expected while the old primary or the owned listener is stopped.
               }
            }
         });
      }

      /************************************************************************
       ** Forward bytes until either endpoint closes; always close both sockets.
       ************************************************************************/
      private static void copy(Socket source, Socket destination)
      {
         try(source; destination)
         {
            source.getInputStream().transferTo(destination.getOutputStream());
         }
         catch(IOException expectedOnDisconnect)
         {
            // Socket termination is the fault injected by this fixture.
         }
      }

      /************************************************************************
       ** Return the stable caller-facing endpoint.
       ************************************************************************/
      int port()
      {
         return (listener.getLocalPort());
      }

      /************************************************************************
       ** Count accepted socket pairs before the first switch.
       ************************************************************************/
      synchronized int socketCount()
      {
         return (sockets.size());
      }

      /************************************************************************
       ** Change only infrastructure routing; terminate all old pooled connections.
       ************************************************************************/
      synchronized void switchTo(HostAndPort target) throws IOException
      {
         this.target = target;
         for(Socket socket : sockets)
         {
            socket.close();
         }
         sockets.clear();
      }

      /************************************************************************
       ** Stop the owned listener and all accepted connections.
       ************************************************************************/
      @Override
      public void close() throws Exception
      {
         listener.close();
         switchTo(target);
         acceptor.join(2000);
         assertFalse(acceptor.isAlive());
      }
   }
}
