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


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/*******************************************************************************
 ** The topology decision of the sibling-container fixtures.
 *******************************************************************************/
class FixtureEndpointTest
{
   private static final String CONTAINER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";



   /***************************************************************************
    ** Build container on the default bridge: dial the sibling bridge IP and its own port, never the published port.
    ***************************************************************************/
   @Test
   void buildContainerOnTheBridgeUsesTheBridgeAddress()
   {
      assertEquals(new FixtureEndpoint.Address("172.17.0.9", 6379), FixtureEndpoint.choose(true, "172.17.0.9", 6379, "localhost", 41111));
      assertEquals(new FixtureEndpoint.Address("172.17.0.9", 6379), FixtureEndpoint.choose(true, "172.17.0.9", 6379, null, null));
   }



   /***************************************************************************
    ** Every other topology (JVM on the Docker host, remote daemon, job network, host networking, unidentified
    ** build container) keeps the original Testcontainers host and published port exactly, whatever the host is.
    ***************************************************************************/
   @Test
   void everyOtherTopologyKeepsTheOriginalPublishedEndpoint()
   {
      assertEquals(new FixtureEndpoint.Address("localhost", 41111), FixtureEndpoint.choose(false, "172.17.0.9", 6379, "localhost", 41111));
      assertEquals(new FixtureEndpoint.Address("docker.example.test", 32768), FixtureEndpoint.choose(false, null, 6379, "docker.example.test", 32768));
      assertEquals(new FixtureEndpoint.Address("host.docker.internal", 40000), FixtureEndpoint.choose(false, null, 6379, "host.docker.internal", 40000));
   }



   /***************************************************************************
    ** An unknown endpoint is an error, never a guessed address.
    ***************************************************************************/
   @Test
   void missingEndpointFailsClosed()
   {
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(true, null, 6379, "localhost", 41111));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(true, " ", 6379, "localhost", 41111));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(false, "172.17.0.9", 6379, "localhost", null));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(false, "172.17.0.9", 6379, " ", 41111));
   }



   /***************************************************************************
    ** The build container id comes from the mount table when present, else the host name.
    ***************************************************************************/
   @Test
   void ownContainerIdPrefersTheMountTableThenTheHostName()
   {
      String mountInfo = "700 699 0:50 /docker/containers/" + CONTAINER_ID + "/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw";
      assertEquals(CONTAINER_ID, FixtureEndpoint.ownContainerId(mountInfo, "unrelated"));
      assertEquals("abc123", FixtureEndpoint.ownContainerId("no container mounts here", " abc123 "));
      assertNull(FixtureEndpoint.ownContainerId("", null));
      assertNull(FixtureEndpoint.ownContainerId(null, " "));
   }

}
