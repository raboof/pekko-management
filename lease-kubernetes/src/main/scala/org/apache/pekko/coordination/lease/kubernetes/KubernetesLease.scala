/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.coordination.lease.kubernetes

import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.coordination.lease.kubernetes.KubernetesLease.makeDNS1039Compatible
import org.apache.pekko.coordination.lease.kubernetes.LeaseActor._
import org.apache.pekko.coordination.lease.kubernetes.internal.KubernetesApiImpl
import org.apache.pekko.coordination.lease.scaladsl.Lease
import org.apache.pekko.coordination.lease.LeaseException
import org.apache.pekko.coordination.lease.LeaseSettings
import org.apache.pekko.coordination.lease.LeaseTimeoutException
import org.apache.pekko.dispatch.ExecutionContexts
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.util.ConstantFun
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

object KubernetesLease {
  val configPath = "pekko.coordination.lease.kubernetes"
  private val leaseCounter = new AtomicInteger(1)

  /**
   * Limit the length of a name to 63 characters.
   * Some subsystem of Kubernetes cannot manage longer names.
   */
  private def truncateTo63Characters(name: String): String = name.take(63)

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String, characters: List[Char]): String =
    name.dropWhile(characters.contains(_)).reverse.dropWhile(characters.contains(_)).reverse

  /**
   * Make a name compatible with DNS 1039 standard: like a single domain name segment.
   * Regex to follow: [a-z]([-a-z0-9]*[a-z0-9])
   * Limit the resulting name to 63 characters
   */
  private def makeDNS1039Compatible(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replaceAll("[_.]", "-").replaceAll("[^-a-z0-9]", "")
    trim(truncateTo63Characters(normalized), List('-'))
  }
}

class KubernetesLease private[pekko] (system: ExtendedActorSystem, leaseTaken: AtomicBoolean, settings: LeaseSettings)
    extends Lease(settings) {

  import org.apache.pekko.pattern.ask
  import system.dispatcher

  private val logger = LoggerFactory.getLogger(classOf[KubernetesLease])

  private val k8sSettings = KubernetesSettings(settings.leaseConfig, settings.timeoutSettings)
  private val k8sApi = new KubernetesApiImpl(system, k8sSettings)
  private implicit val timeout: Timeout = Timeout(settings.timeoutSettings.operationTimeout)

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)

  private val leaseName = makeDNS1039Compatible(settings.leaseName)
  private val leaseActor = system.systemActorOf(
    LeaseActor.props(k8sApi, settings, leaseName, leaseTaken),
    s"kubernetesLease${KubernetesLease.leaseCounter.incrementAndGet}")
  if (leaseName != settings.leaseName) {
    logger.info(
      "Original lease name [{}] sanitized for kubernetes: [{}]",
      Array[Object](settings.leaseName, leaseName): _*)
  }
  logger.debug(
    "Starting kubernetes lease actor [{}] for lease [{}], owner [{}]",
    leaseActor,
    leaseName,
    settings.ownerName)

  override def checkLease(): Boolean = leaseTaken.get()

  override def release(): Future[Boolean] = {
    // replace with transform once 2.11 dropped
    (leaseActor ? Release())
      .flatMap {
        case LeaseReleased       => Future.successful(true)
        case InvalidRequest(msg) => Future.failed(new LeaseException(msg))
      }(ExecutionContexts.parasitic)
      .recoverWith {
        case _: AskTimeoutException =>
          Future.failed(
            new LeaseTimeoutException(
              s"Timed out trying to release lease [${leaseName}, ${settings.ownerName}]. It may still be taken."))
      }
  }

  override def acquire(): Future[Boolean] = {
    acquire(ConstantFun.scalaAnyToUnit)

  }
  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    // replace with transform once 2.11 dropped
    (leaseActor ? Acquire(leaseLostCallback))
      .flatMap {
        case LeaseAcquired       => Future.successful(true)
        case LeaseTaken          => Future.successful(false)
        case InvalidRequest(msg) => Future.failed(new LeaseException(msg))
      }
      .recoverWith {
        case _: AskTimeoutException =>
          Future.failed[Boolean](new LeaseTimeoutException(
            s"Timed out trying to acquire lease [${leaseName}, ${settings.ownerName}]. It may still be taken."))
      }(ExecutionContexts.parasitic)
  }
}