/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package yaooqinn.kyuubi.server

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.spark.{KyuubiSparkUtil, SparkConf}
import org.apache.spark.KyuubiConf._
import yaooqinn.kyuubi.ha.{FailoverService, HighAvailableService, LoadBalanceService}
import yaooqinn.kyuubi.metrics.MetricsSystem
import yaooqinn.kyuubi.service.{CompositeService, ServiceException}

import org.apache.kyuubi.Logging

/**
 * Main entrance of Kyuubi Server
 */
private[kyuubi] class KyuubiServer private(name: String)
  extends CompositeService(name) with Logging {

  private var _beService: BackendService = _
  def beService: BackendService = _beService
  private var _feService: FrontendService = _
  def feService: FrontendService = _feService
  private var _haService: HighAvailableService = _

  private val started = new AtomicBoolean(false)

  private var _isDeregisterWithZK = false
  def deregisterWithZK(): Unit = _isDeregisterWithZK = true
  def isDeregisterWithZk: Boolean = _isDeregisterWithZK

  def this() = this(classOf[KyuubiServer].getSimpleName)

  override def init(conf: SparkConf): Unit = synchronized {
    this.conf = conf

    MetricsSystem.init(conf)

    val OOMHook = new Runnable {
      override def run(): Unit = KyuubiServer.this.stop()
    }

    _beService = new BackendService(this)
    _feService = new FrontendService(_beService, OOMHook)

    addService(_beService)
    addService(_feService)
    if (conf.getBoolean(HA_ENABLED.key, defaultValue = false)) {
      _haService = if (conf.getOption(HA_MODE.key).exists(_.equalsIgnoreCase("failover"))) {
        new FailoverService(this)
      } else {
        new LoadBalanceService(this)
      }
      addService(_haService)
    }
    super.init(conf)
  }

  override def start(): Unit = {
    super.start()
    started.set(true)
  }

  override def stop(): Unit = {
    info("Shutting down " + name)
    if (started.getAndSet(false)) {
      super.stop()
    }
    MetricsSystem.close()
  }
}

object KyuubiServer extends Logging {

  def startKyuubiServer(): KyuubiServer = {
    try {
      KyuubiSparkUtil.initDaemon(logger)
      validate()
      val conf = new SparkConf(loadDefaults = true)
      KyuubiSparkUtil.setupCommonConfig(conf)
      val server = new KyuubiServer()
      KyuubiSparkUtil.addShutdownHook(server.stop())
      server.init(conf)
      server.start()
      info(server.getName + " started!")
      server
    } catch {
      case e: Exception => throw e
    }
  }

  private[kyuubi] def validate(): Unit = {
    if (KyuubiSparkUtil.majorVersion(KyuubiSparkUtil.SPARK_VERSION) < 2) {
      throw new ServiceException(s"${KyuubiSparkUtil.SPARK_VERSION} is too old for Kyuubi" +
        s" Server.")
    }
  }
}
