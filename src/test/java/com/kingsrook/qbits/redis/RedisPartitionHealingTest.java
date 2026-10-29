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

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.exceptions.JedisDataException;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Controlled fail-closed promotion: the old primary remains alive but fenced.
 *******************************************************************************/
class RedisPartitionHealingTest
{
   /***************************************************************************
    ** Losing replication stops old writes before promotion; healing demotes it.
    ***************************************************************************/
   @Test
   void liveOldPrimaryRejectsWritesAcrossPartitionAndHealing() throws Exception
   {
      try(var network = Network.newNetwork();
          var primary = new GenericContainer<>("valkey/valkey:9.1.3-alpine")
             .withNetwork(network).withNetworkAliases("primary").withExposedPorts(6379)
             .withCommand("valkey-server", "--min-replicas-to-write", "1", "--min-replicas-max-lag", "1");
          var replica = new GenericContainer<>("valkey/valkey:9.1.3-alpine")
             .withNetwork(network).withExposedPorts(6379)
             .withCommand("valkey-server", "--replicaof", "primary", "6379"))
      {
         primary.start();
         replica.start();
         try(var oldPool = connection(primary))
         {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
            {
               while(true)
               {
                  try
                  {
                     assertEquals("OK", oldPool.client().set("partition-marker", "before"));
                     break;
                  }
                  catch(JedisDataException waitingForReplica)
                  {
                     assertTrue(waitingForReplica.getMessage().contains("NOREPLICAS"));
                     Thread.sleep(25);
                  }
               }
               while(!"before".equals(command(replica, "GET", "partition-marker")))
               {
                  Thread.sleep(25);
               }
            });

            var docker = DockerClientFactory.instance().client();
            docker.disconnectFromNetworkCmd().withContainerId(replica.getContainerId()).withNetworkId(network.getId()).exec();
            long partitionStarted = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
            {
               while(true)
               {
                  try
                  {
                     oldPool.client().set("fencing-window", "not-authoritative");
                  }
                  catch(JedisDataException fenced)
                  {
                     assertTrue(fenced.getMessage().contains("NOREPLICAS"));
                     break;
                  }
                  Thread.sleep(25);
               }
            });
            assertTrue(primary.isRunning());
            assertEquals("PONG", oldPool.client().ping());
            assertThrows(JedisDataException.class, () -> oldPool.client().set("must-not-exist", "stale"));
            assertNull(oldPool.client().get("must-not-exist"));
            System.out.println("LIVE_PRIMARY_FENCING_MS " + Duration.ofNanos(System.nanoTime() - partitionStarted).toMillis());

            // Promotion is authorized only after the old primary's rejection above.
            assertEquals("OK", command(replica, "REPLICAOF", "NO", "ONE"));
            assertEquals("OK", command(replica, "SET", "partition-marker", "after"));
            docker.connectToNetworkCmd().withContainerId(replica.getContainerId()).withNetworkId(network.getId()).exec();
            var address = docker.inspectContainerCmd(replica.getContainerId()).exec().getNetworkSettings().getNetworks()
               .values().iterator().next().getIpAddress();
            assertEquals("OK", command(primary, "REPLICAOF", address, "6379"));
            assertEquals("OK", command(replica, "CONFIG", "SET", "min-replicas-to-write", "1"));
            assertEquals("OK", command(replica, "CONFIG", "SET", "min-replicas-max-lag", "1"));
            assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
            {
               while(!"after".equals(oldPool.client().get("partition-marker")))
               {
                  Thread.sleep(25);
               }
               while(!"OK".equals(command(replica, "SET", "healed-marker", "replicated")))
               {
                  Thread.sleep(25);
               }
               while(!"replicated".equals(oldPool.client().get("healed-marker")))
               {
                  Thread.sleep(25);
               }
            });
            var readOnly = assertThrows(JedisDataException.class, () -> oldPool.client().set("must-not-exist", "stale"));
            assertTrue(readOnly.getMessage().contains("READONLY"));
            assertEquals(0, oldPool.metrics().active());
            assertEquals(2, oldPool.metrics().maximum());
         }
      }
   }

   /***************************************************************************
    ** Bounded actual QQQ pool connected to the original endpoint throughout.
    ***************************************************************************/
   private static RedisConnection connection(GenericContainer<?> endpoint)
   {
      return (new RedisConnection(new HostAndPort(endpoint.getHost(), endpoint.getMappedPort(6379)),
         DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(1000).build(),
         2, Duration.ofMillis(500)));
   }

   /***************************************************************************
    ** Administration of disposable fixture servers, never application hosts.
    ***************************************************************************/
   private static String command(GenericContainer<?> server, String... arguments) throws Exception
   {
      var invocation = new String[arguments.length + 1];
      invocation[0] = "valkey-cli";
      System.arraycopy(arguments, 0, invocation, 1, arguments.length);
      var result = server.execInContainer(invocation);
      assertEquals(0, result.getExitCode());
      return (result.getStdout().trim());
   }
}
