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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Generate disposable certificates and exercise actual Redis TLS handshakes.
 *******************************************************************************/
class RedisTlsTest
{
   @TempDir
   Path temporary;

   /***************************************************************************
    ** Trust is required and cannot disable hostname verification.
    ***************************************************************************/
   @Test
   void trustedTlsAndHostnameVerification() throws Exception
   {
      exercise(false);
      exercise(true);
   }

   /***************************************************************************
    ** The invalid-host case uses a trusted certificate with the wrong identity.
    ***************************************************************************/
   private void exercise(boolean wrongHostname) throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         Path directory = Files.createDirectory(temporary.resolve(wrongHostname ? "wrong" : "valid"));
         String host = redis.getHost();
         String identity = wrongHostname ? "dns:wrong-host.invalid" : (host.matches("[0-9.]+") || host.contains(":") ? "ip:" : "dns:") + host;
         TrustedContext.context = certificate(directory, identity);
         redis.withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("cert.pem"), 0444), "/test-cert.pem")
            .withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("key.pem"), 0444), "/test-key.pem")
            .withCommand("redis-server", "--port", "0", "--tls-port", "6379", "--tls-cert-file", "/test-cert.pem",
               "--tls-key-file", "/test-key.pem", "--tls-ca-cert-file", "/test-cert.pem", "--tls-auth-clients", "no",
               "--requirepass", RedisAuthenticationTest.TEST_PASSWORD);
         redis.start();
         var settings = new RedisConnectionConfig().withHost(host).withPort(redis.getMappedPort(6379))
            .withConnectionTimeoutMillis(1000).withSocketTimeoutMillis(1000)
            .withCredentialsProvider(new QCodeReference(RedisAuthenticationTest.ValidCredentials.class));
         var instance = RedisRuntimeTest.hostInstance();
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence", settings)).produce(instance);
         var runtime = new RedisRuntimeService();
         try
         {
            assertThrows(QException.class, () -> runtime.start(instance), "The JVM must reject the untrusted test certificate");
            settings.withTlsProvider(new QCodeReference(TrustedContext.class));
            if(wrongHostname)
            {
               assertThrows(QException.class, () -> runtime.start(instance), "Trust must not bypass hostname verification");
               assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "presence"));
            }
            else
            {
               runtime.start(instance);
               assertEquals("PONG", RedisRuntimeService.connection(instance, "presence").client().ping());
            }
         }
         finally
         {
            runtime.stop();
            runtime.afterApplicationStop();
            TrustedContext.context = null;
         }
      }
   }

   /***************************************************************************
    ** Redis must require a trusted client identity as well as server verification.
    ***************************************************************************/
   @Test
   void mutualTlsRequiresTrustedClientCertificate() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         Path server = Files.createDirectory(temporary.resolve("server"));
         Path client = Files.createDirectory(temporary.resolve("client"));
         String host = redis.getHost();
         String identity = (host.matches("[0-9.]+") || host.contains(":") ? "ip:" : "dns:") + host;
         SSLContext trustOnly = certificate(server, identity);
         certificate(client, "dns:client-fixture.invalid");
         redis.withCopyFileToContainer(MountableFile.forHostPath(server.resolve("cert.pem"), 0444), "/server-cert.pem")
            .withCopyFileToContainer(MountableFile.forHostPath(server.resolve("key.pem"), 0444), "/server-key.pem")
            .withCopyFileToContainer(MountableFile.forHostPath(client.resolve("cert.pem"), 0444), "/client-trust.pem")
            .withCommand("redis-server", "--port", "0", "--tls-port", "6379",
               "--tls-cert-file", "/server-cert.pem", "--tls-key-file", "/server-key.pem",
               "--tls-ca-cert-file", "/client-trust.pem", "--tls-auth-clients", "yes",
               "--requirepass", RedisAuthenticationTest.TEST_PASSWORD);
         redis.start();
         var settings = new RedisConnectionConfig().withHost(host).withPort(redis.getMappedPort(6379))
            .withConnectionTimeoutMillis(1000).withSocketTimeoutMillis(1000)
            .withTlsProvider(new QCodeReference(TrustedContext.class))
            .withCredentialsProvider(new QCodeReference(RedisAuthenticationTest.ValidCredentials.class));
         var instance = RedisRuntimeTest.hostInstance();
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence", settings)).produce(instance);
         var runtime = new RedisRuntimeService();
         try
         {
            TrustedContext.context = trustOnly;
            assertThrows(QException.class, () -> runtime.start(instance), "Server trust alone must not satisfy mutual TLS");
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "presence"));
            TrustedContext.context = clientContext(server, server);
            assertThrows(QException.class, () -> runtime.start(instance), "An unrelated client identity must not authenticate");
            assertThrows(IllegalStateException.class, () -> RedisRuntimeService.connection(instance, "presence"));
            TrustedContext.context = clientContext(server, client);
            runtime.start(instance);
            assertEquals("PONG", RedisRuntimeService.connection(instance, "presence").client().ping());
            assertEquals(com.kingsrook.qqq.backend.core.health.HealthStatus.UP, new RedisHealthCheck().run(
               new com.kingsrook.qqq.backend.core.health.HealthCheckInput().withQInstance(instance)).getStatus());
         }
         finally
         {
            runtime.stop();
            runtime.afterApplicationStop();
            TrustedContext.context = null;
         }
      }
   }

   /***************************************************************************
    ** Keep server trust independent of the client key presented to Redis.
    ***************************************************************************/
   private static SSLContext clientContext(Path server, Path client) throws Exception
   {
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try(var input = Files.newInputStream(client.resolve("test.p12")))
      {
         keys.load(input, "test-only-password".toCharArray());
      }
      var keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(keys, "test-only-password".toCharArray());
      KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
      trust.load(null, null);
      try(var input = Files.newInputStream(server.resolve("cert.pem")))
      {
         trust.setCertificateEntry("server", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
      }
      var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trust);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
      return (context);
   }

   /***************************************************************************
    ** JDK keytool avoids internal JDK APIs and external certificate dependencies.
    ***************************************************************************/
   private static SSLContext certificate(Path directory, String identity) throws Exception
   {
      String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
      Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
      Path store = directory.resolve("test.p12");
      var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "redis", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "2", "-dname", "CN=redis-test", "-ext", "SAN=" + identity, "-keystore", store.toString(),
         "-storetype", "PKCS12", "-storepass", "test-only-password", "-noprompt")
         .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
      if(!process.waitFor(30, TimeUnit.SECONDS))
      {
         process.destroyForcibly();
         fail("Certificate generation exceeded its deadline");
      }
      assertEquals(0, process.exitValue(), "Certificate generation failed");
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try(var input = Files.newInputStream(store))
      {
         keys.load(input, "test-only-password".toCharArray());
      }
      var certificate = keys.getCertificate("redis");
      Files.writeString(directory.resolve("cert.pem"), pem("CERTIFICATE", certificate.getEncoded()));
      Files.writeString(directory.resolve("key.pem"), pem("PRIVATE KEY", keys.getKey("redis", "test-only-password".toCharArray()).getEncoded()));
      KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
      trust.load(null, null);
      trust.setCertificateEntry("redis", certificate);
      TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      managers.init(trust);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, managers.getTrustManagers(), null);
      return (context);
   }

   /***************************************************************************
    ** Encode temporary test material in Redis's standard PEM format.
    ***************************************************************************/
   private static String pem(String kind, byte[] bytes)
   {
      return ("-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
         + "\n-----END " + kind + "-----\n");
   }

   /***************************************************************************
    ** Test-only host integration; production providers resolve their own trust roots.
    ***************************************************************************/
   public static class TrustedContext implements RedisTlsProvider
   {
      static SSLContext context;

      /***********************************************************************
       ** Return only the context for the current disposable test certificate.
       ***********************************************************************/
      @Override
      public SSLContext getContext()
      {
         return (context);
      }
   }
}
