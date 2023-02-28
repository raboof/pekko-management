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

package org.apache.pekko.management.loglevels.logback

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.event.{ Logging => ClassicLogging }
import org.apache.pekko.http.javadsl.server.MalformedQueryParamRejection
import org.apache.pekko.http.scaladsl.model.{ StatusCodes, Uri }
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.management.scaladsl.ManagementRouteProviderSettings
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

class LogLevelRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  override def testConfigSource: String =
    """
      pekko.loglevel = INFO
      """

  val routes = LogLevelRoutes
    .createExtension(system.asInstanceOf[ExtendedActorSystem])
    .routes(ManagementRouteProviderSettings(Uri("https://example.com"), readOnly = false))

  "The logback log level routes" must {

    "show log level of a Logger" in {
      Get("/loglevel/logback?logger=LogLevelRoutesSpec") ~> routes ~> check {
        responseAs[String]
      }
    }

    "change log level of a Logger" in {
      Put("/loglevel/logback?logger=LogLevelRoutesSpec&level=DEBUG") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        LoggerFactory.getLogger("LogLevelRoutesSpec").isDebugEnabled should ===(true)
      }
    }

    "fail for unknown log level" in {
      Put("/loglevel/logback?logger=LogLevelRoutesSpec&level=MONKEY") ~> routes ~> check {
        rejection shouldBe an[MalformedQueryParamRejection]
      }
    }

    "not change loglevel if read only" in {
      val readOnlyRoutes = LogLevelRoutes
        .createExtension(system.asInstanceOf[ExtendedActorSystem])
        .routes(ManagementRouteProviderSettings(Uri("https://example.com"), readOnly = true))
      Put("/loglevel/logback?logger=LogLevelRoutesSpec&level=DEBUG") ~> readOnlyRoutes ~> check {
        response.status should ===(StatusCodes.Forbidden)
      }
    }

    "allow inspecting classic Pekko loglevel" in {
      Get("/loglevel/pekko") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        responseAs[String] should ===("INFO")
      }
    }

    "allow changing classic Pekko loglevel" in {
      Put("/loglevel/pekko?level=DEBUG") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        system.eventStream.logLevel should ===(ClassicLogging.DebugLevel)
      }
    }
  }

}