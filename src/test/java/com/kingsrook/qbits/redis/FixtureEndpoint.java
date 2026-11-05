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


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;


/*******************************************************************************
 ** Where a disposable test container is reachable FROM THIS JVM.
 **
 ** The original behavior is Testcontainers' own: the host it reports and the
 ** published port. That is kept everywhere except one measured topology: a JVM
 ** inside a build container that shares the daemon's default bridge with the
 ** fixture (the full Linux verify, which talks to the host daemon through its
 ** socket). On the Docker Desktop runner the published port is reached through
 ** the host-gateway hop; measured on an idle machine that path showed round trips
 ** of about 1 ms with stalls of 0.3 s and about 2 s, against about 0.13 ms and
 ** no stalls when dialing the sibling directly on the bridge. These tests use
 ** 250 ms to 1 s socket timeouts, so such stalls can expire them; that is a
 ** plausible contributor, not shown to be the only cause of the observed
 ** "Read timed out" failures. In that one topology the fixture is dialed on its
 ** bridge address and its own port.
 **
 ** The choice is made from the daemon's own description of the build container's
 ** networks (no connection probing). If the build container cannot be identified
 ** or is not on the default bridge (a job network, host networking, a remote
 ** daemon), the original published endpoint is used unchanged. Timeouts,
 ** scenarios, assertions and server configuration are unchanged.
 *******************************************************************************/
final class FixtureEndpoint
{
   private static final String DEFAULT_BRIDGE = "bridge";

   private static final Pattern MOUNTINFO_CONTAINER_ID = Pattern.compile("/docker/containers/([0-9a-f]{64})/");

   private static volatile Boolean buildContainerOnDefaultBridge;



   /***************************************************************************
    ** Host and port to dial.
    ***************************************************************************/
   record Address(String host, int port)
   {
   }



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private FixtureEndpoint()
   {
   }



   /***************************************************************************
    ** Host this JVM must dial for the container.
    ***************************************************************************/
   static String host(GenericContainer<?> container)
   {
      return (choose(onBridgeWithBuildContainer(), bridgeIp(container), 6379, container.getHost(), publishedPort(container)).host());
   }



   /***************************************************************************
    ** Port matching {@link #host(GenericContainer)}.
    ***************************************************************************/
   static int port(GenericContainer<?> container)
   {
      return (choose(onBridgeWithBuildContainer(), bridgeIp(container), 6379, container.getHost(), publishedPort(container)).port());
   }



   /***************************************************************************
    ** A container that lives only on a custom test network is also attached to the
    ** default bridge when (and only when) the build container is on that bridge, so
    ** that the build container can reach it directly. The test network, and therefore
    ** every partition scenario between the fixture servers, is untouched: attach only
    ** the container the test client dials, never one whose reachability the test cuts
    ** or whose peers must stay isolated from it. No-op in every other topology.
    ***************************************************************************/
   static void attachToDefaultBridgeWhenInsideContainer(GenericContainer<?> container)
   {
      if(!onBridgeWithBuildContainer())
      {
         return;
      }
      var docker = container.getDockerClient();
      var networks = docker.inspectContainerCmd(container.getContainerId()).exec().getNetworkSettings().getNetworks();
      if(networks == null || !networks.containsKey(DEFAULT_BRIDGE))
      {
         docker.connectToNetworkCmd().withContainerId(container.getContainerId()).withNetworkId(DEFAULT_BRIDGE).exec();
      }
   }



   /***************************************************************************
    ** Whether this JVM runs in a container that the daemon reports on the default
    ** bridge. Decided once per JVM from the daemon's description of this container.
    ***************************************************************************/
   private static boolean onBridgeWithBuildContainer()
   {
      Boolean known = buildContainerOnDefaultBridge;
      if(known == null)
      {
         known = decideBuildContainerOnDefaultBridge();
         buildContainerOnDefaultBridge = known;
      }
      return (known);
   }



   /***************************************************************************
    ** Inspect the build container; anything that cannot be established means the
    ** original published endpoint (false).
    ***************************************************************************/
   private static boolean decideBuildContainerOnDefaultBridge()
   {
      if(!Files.exists(Path.of("/.dockerenv")))
      {
         return (false);
      }
      try
      {
         String id = ownContainerId(Files.readString(Path.of("/proc/self/mountinfo")), System.getenv("HOSTNAME"));
         if(id == null)
         {
            return (false);
         }
         var networks = DockerClientFactory.instance().client().inspectContainerCmd(id).exec().getNetworkSettings().getNetworks();
         return (networks != null && networks.containsKey(DEFAULT_BRIDGE));
      }
      catch(Exception e)
      {
         return (false);
      }
   }



   /***************************************************************************
    ** The id of the container this JVM runs in: from the mount table when it names
    ** the container directory, otherwise the host name (the container id unless
    ** overridden); null when neither exists.
    ***************************************************************************/
   static String ownContainerId(String mountInfo, String hostName)
   {
      Matcher matcher = MOUNTINFO_CONTAINER_ID.matcher(mountInfo == null ? "" : mountInfo);
      if(matcher.find())
      {
         return (matcher.group(1));
      }
      return (hostName == null || hostName.isBlank() ? null : hostName.trim());
   }



   /***************************************************************************
    ** The container's address on the default bridge, or null when it has none.
    ***************************************************************************/
   private static String bridgeIp(GenericContainer<?> container)
   {
      if(!onBridgeWithBuildContainer())
      {
         return (null);
      }
      InspectContainerResponse inspected = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec();
      var networks = inspected.getNetworkSettings().getNetworks();
      return (networks == null || networks.get(DEFAULT_BRIDGE) == null ? null : networks.get(DEFAULT_BRIDGE).getIpAddress());
   }



   /***************************************************************************
    ** Testcontainers' published port for the Redis port, or null when the build
    ** container dials the bridge (so the published port is not consulted).
    ***************************************************************************/
   private static Integer publishedPort(GenericContainer<?> container)
   {
      return (onBridgeWithBuildContainer() ? null : container.getMappedPort(6379));
   }



   /***************************************************************************
    ** Pure decision, separately tested. With the build container on the default
    ** bridge: the sibling's bridge address and its own port. Otherwise the published
    ** endpoint exactly as Testcontainers reports it.
    ***************************************************************************/
   static Address choose(boolean buildContainerOnDefaultBridge, String bridgeIp, int internalPort, String publishedHost, Integer publishedPort)
   {
      if(buildContainerOnDefaultBridge)
      {
         if(bridgeIp == null || bridgeIp.isBlank())
         {
            throw new IllegalStateException("FIXTURE_ENDPOINT_NO_BRIDGE_ADDRESS: the build container cannot reach a sibling without its bridge address");
         }
         return (new Address(bridgeIp, internalPort));
      }
      if(publishedHost == null || publishedHost.isBlank() || publishedPort == null)
      {
         throw new IllegalStateException("FIXTURE_ENDPOINT_NOT_PUBLISHED: no published endpoint to dial");
      }
      return (new Address(publishedHost, publishedPort));
   }

}
