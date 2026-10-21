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

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;

/*******************************************************************************
 ** Serializable connection settings. Credentials are resolved only at runtime.
 *******************************************************************************/
public class RedisConnectionConfig implements Serializable
{
   private String host = "localhost";
   private Integer port = 6379;
   private Boolean tls = true;
   private Integer connectionTimeoutMillis = 2000;
   private Integer socketTimeoutMillis = 2000;
   private Integer maximumConnections = 16;
   private Integer maximumWaitMillis = 500;
   private QCodeReference credentialsProvider = null;
   private QCodeReference tlsProvider = null;

   /***************************************************************************
    ** Validate bounds without exposing endpoint or credential values in errors.
    ***************************************************************************/
   public void validate(List<String> errors)
   {
      if(host == null || host.isBlank() || host.contains("://") || host.contains("@"))
      {
         errors.add("Redis host must be a hostname, not a credential-bearing URI");
      }
      if(port == null || port < 1 || port > 65535)
      {
         errors.add("Redis port must be between 1 and 65535");
      }
      if(tls == null)
      {
         errors.add("Redis TLS must be explicitly enabled or disabled");
      }
      if(tlsProvider != null && !Boolean.TRUE.equals(tls))
      {
         errors.add("Redis TLS provider requires TLS to be enabled");
      }
      if(connectionTimeoutMillis == null || connectionTimeoutMillis < 1 || socketTimeoutMillis == null || socketTimeoutMillis < 1
         || maximumConnections == null || maximumConnections < 1 || maximumWaitMillis == null || maximumWaitMillis < 1)
      {
         errors.add("Redis timeouts and pool size must be positive");
      }
   }

   /***************************************************************************
    ** Return host.
    ***************************************************************************/
   public String getHost()
   {
      return (host);
   }

   /***************************************************************************
    ** Configure host.
    ***************************************************************************/
   public RedisConnectionConfig withHost(String value)
   {
      host = value;
      return (this);
   }

   /***************************************************************************
    ** Return port.
    ***************************************************************************/
   public Integer getPort()
   {
      return (port);
   }

   /***************************************************************************
    ** Configure port.
    ***************************************************************************/
   public RedisConnectionConfig withPort(Integer value)
   {
      port = value;
      return (this);
   }

   /***************************************************************************
    ** Return tls.
    ***************************************************************************/
   public Boolean getTls()
   {
      return (tls);
   }

   /***************************************************************************
    ** Configure tls.
    ***************************************************************************/
   public RedisConnectionConfig withTls(Boolean value)
   {
      tls = value;
      return (this);
   }

   /***************************************************************************
    ** Return connectionTimeoutMillis.
    ***************************************************************************/
   public Integer getConnectionTimeoutMillis()
   {
      return (connectionTimeoutMillis);
   }

   /***************************************************************************
    ** Configure connectionTimeoutMillis.
    ***************************************************************************/
   public RedisConnectionConfig withConnectionTimeoutMillis(Integer value)
   {
      connectionTimeoutMillis = value;
      return (this);
   }

   /***************************************************************************
    ** Return socketTimeoutMillis.
    ***************************************************************************/
   public Integer getSocketTimeoutMillis()
   {
      return (socketTimeoutMillis);
   }

   /***************************************************************************
    ** Configure socketTimeoutMillis.
    ***************************************************************************/
   public RedisConnectionConfig withSocketTimeoutMillis(Integer value)
   {
      socketTimeoutMillis = value;
      return (this);
   }

   /***************************************************************************
    ** Return maximumConnections.
    ***************************************************************************/
   public Integer getMaximumConnections()
   {
      return (maximumConnections);
   }

   /***************************************************************************
    ** Configure maximumConnections.
    ***************************************************************************/
   public RedisConnectionConfig withMaximumConnections(Integer value)
   {
      maximumConnections = value;
      return (this);
   }

   /***************************************************************************
    ** Return maximumWaitMillis.
    ***************************************************************************/
   public Integer getMaximumWaitMillis()
   {
      return (maximumWaitMillis);
   }

   /***************************************************************************
    ** Configure maximumWaitMillis.
    ***************************************************************************/
   public RedisConnectionConfig withMaximumWaitMillis(Integer value)
   {
      maximumWaitMillis = value;
      return (this);
   }

   /***************************************************************************
    ** Return credentialsProvider.
    ***************************************************************************/
   public QCodeReference getCredentialsProvider()
   {
      return (credentialsProvider);
   }

   /***************************************************************************
    ** Configure credentialsProvider.
    ***************************************************************************/
   public RedisConnectionConfig withCredentialsProvider(QCodeReference value)
   {
      credentialsProvider = value;
      return (this);
   }

   /***************************************************************************
    ** Runtime host integration for private trust roots and client certificates.
    ***************************************************************************/
   public QCodeReference getTlsProvider()
   {
      return (tlsProvider);
   }

   /***************************************************************************
    ** Configure TLS context resolution without embedding keys or passwords.
    ***************************************************************************/
   public RedisConnectionConfig withTlsProvider(QCodeReference value)
   {
      tlsProvider = value;
      return (this);
   }
}
