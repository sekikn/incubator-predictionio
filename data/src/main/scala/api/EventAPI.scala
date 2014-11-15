/** Copyright 2014 TappingStone, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package io.prediction.data.api

import io.prediction.data.Utils
import io.prediction.data.storage.AccessKey
import io.prediction.data.storage.AccessKeys
import io.prediction.data.storage.Event
import io.prediction.data.storage.EventJson4sSupport
import io.prediction.data.storage.Events
import io.prediction.data.storage.StorageError
import io.prediction.data.storage.Storage

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.io.IO
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout

import org.json4s.DefaultFormats
//import org.json4s.ext.JodaTimeSerializers

import spray.can.Http
import spray.http.HttpCharsets
import spray.http.HttpEntity
import spray.http.HttpResponse
import spray.http.MediaTypes
import spray.http.StatusCodes
import spray.httpx.Json4sSupport
import spray.httpx.unmarshalling.Unmarshaller
import spray.routing._
import spray.routing.authentication.Authentication
import spray.routing.Directives._

import scala.concurrent.Future

import java.util.concurrent.TimeUnit

class EventServiceActor(
  val eventClient: Events,
  val accessKeysClient: AccessKeys) extends HttpServiceActor {

  object Json4sProtocol extends Json4sSupport {
    implicit def json4sFormats = DefaultFormats +
      new EventJson4sSupport.APISerializer
    //implicit def json4sFormats: Formats = DefaultFormats.lossless ++
    //  JodaTimeSerializers.all
  }

  import Json4sProtocol._

  val log = Logging(context.system, this)

  // we use the enclosing ActorContext's or ActorSystem's dispatcher for our
  // Futures
  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  // for better message response
  val rejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(msg, _) :: _ =>
      complete(StatusCodes.BadRequest, Map("message" -> msg))
    case MissingQueryParamRejection(msg) :: _ =>
      complete(StatusCodes.NotFound,
        Map("message" -> s"missing required query parameter ${msg}."))
    case AuthenticationFailedRejection(cause, challengeHeaders) :: _ =>
      complete(StatusCodes.Unauthorized, challengeHeaders,
        Map("message" -> s"Invalid accessKey."))
  }

  val jsonPath = """(.+)\.json$""".r

  /* with accessKey in query, return appId if succeed */
  def withAccessKey: RequestContext => Future[Authentication[Int]] = {
    ctx: RequestContext =>
      val accessKeyOpt = ctx.request.uri.query.get("accessKey")
      Future {
        accessKeyOpt.map { accessKey =>
          val accessKeyOpt = accessKeysClient.get(accessKey)
          accessKeyOpt match {
            case Some(k) => Right(k.appid)
            case None => Left(AuthenticationFailedRejection(
              AuthenticationFailedRejection.CredentialsRejected, List()))
          }
        }.getOrElse { Left(AuthenticationFailedRejection(
          AuthenticationFailedRejection.CredentialsMissing, List()))
        }
      }
  }

  val route: Route =
    pathSingleSlash {
      get {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete(Map("status" -> "alive"))
        }
      }
    } ~
    path("events" / jsonPath ) { eventId =>
      get {
        handleRejections(rejectionHandler) {
          authenticate(withAccessKey) { appId =>
            respondWithMediaType(MediaTypes.`application/json`) {
              complete {
                log.debug(s"GET event ${eventId}.")
                val data = eventClient.futureGet(eventId, appId).map { r =>
                  r match {
                    case Left(StorageError(message)) =>
                      (StatusCodes.InternalServerError,
                        Map("message" -> message))
                    case Right(eventOpt) => {
                      eventOpt.map( event =>
                        (StatusCodes.OK, event)
                      ).getOrElse(
                        (StatusCodes.NotFound, Map("message" -> "Not Found"))
                      )
                    }
                  }
                }
                data
              }
            }
          }
        }
      } ~
      delete {
        handleRejections(rejectionHandler) {
          authenticate(withAccessKey) { appId =>
            respondWithMediaType(MediaTypes.`application/json`) {
              complete {
                log.debug(s"DELETE event ${eventId}.")
                val data = eventClient.futureDelete(eventId, appId).map { r =>
                  r match {
                    case Left(StorageError(message)) =>
                      (StatusCodes.InternalServerError,
                        Map("message" -> message))
                    case Right(found) =>
                      if (found) {
                        (StatusCodes.OK, Map("message" -> "Found"))
                      } else {
                        (StatusCodes.NotFound, Map("message" -> "Not Found"))
                      }
                  }
                }
                data
              }
            }
          }
        }
      }
    } ~
    path("events.json") {
      post {
        handleRejections(rejectionHandler) {
          authenticate(withAccessKey) { appId =>
            entity(as[Event]) { event =>
              complete {
                log.debug(s"POST events")
                val data = eventClient.futureInsert(event, appId).map { r =>
                  r match {
                    case Left(StorageError(message)) =>
                      (StatusCodes.InternalServerError,
                        Map("message" -> message))
                    case Right(id) =>
                      (StatusCodes.Created, Map("eventId" -> s"${id}"))
                  }
                }
                data
              }
            }
          }
        }
      } ~
      get {
        handleRejections(rejectionHandler) {
          authenticate(withAccessKey) { appId =>
            parameters(
              'startTime.as[Option[String]],
              'untilTime.as[Option[String]],
              'entityType.as[Option[String]],
              'entityId.as[Option[String]],
              'event.as[Option[String]],
              'limit.as[Option[Int]],
              'reversed.as[Option[Boolean]]) {
              (startTimeStr, untilTimeStr, entityType, entityId,
                eventName,
                limit, reversed) =>
              respondWithMediaType(MediaTypes.`application/json`) {
                complete {
                  log.debug(
                    s"GET events of appId=${appId} " +
                    s"st=${startTimeStr} ut=${untilTimeStr} " +
                    s"et=${entityType} eid=${entityId} " +
                    s"li=${limit} rev=${reversed} ")

                  val parseTime = Future {
                    val startTime = startTimeStr.map(Utils.stringToDateTime(_))
                    val untilTime = untilTimeStr.map(Utils.stringToDateTime(_))
                    (startTime, untilTime)
                  }

                  parseTime.flatMap { case (startTime, untilTime) =>
                    val data = eventClient.futureGetGeneral(
                      appId = appId,
                      startTime = startTime,
                      untilTime = untilTime,
                      entityType = entityType,
                      entityId = entityId,
                      eventNames = eventName.map(List(_)),
                      limit = limit.orElse(Some(20)),
                      reversed = reversed)
                      .map { r =>
                        r match {
                          case Left(StorageError(message)) =>
                            (StatusCodes.InternalServerError,
                              Map("message" -> message))
                          case Right(eventIter) =>
                            if (eventIter.hasNext)
                              (StatusCodes.OK, eventIter.toArray)
                            else
                              (StatusCodes.NotFound,
                                Map("message" -> "Not Found"))
                        }
                      }
                    data
                  }.recover {
                    case e: Exception =>
                      (StatusCodes.BadRequest, Map("message" -> s"${e}"))
                  }
                }
              }
            }
          }
        }
      }
    }

  def receive = runRoute(route)

}

/* message */
case class StartServer(
  val host: String,
  val port: Int
)

class EventServerActor(
  val eventClient: Events,
  val accessKeysClient: AccessKeys) extends Actor {
  val log = Logging(context.system, this)
  val child = context.actorOf(
    Props(classOf[EventServiceActor], eventClient, accessKeysClient),
    "EventServiceActor")
  implicit val system = context.system

  def receive = {
    case StartServer(host, portNum) => {
      IO(Http) ! Http.Bind(child, interface = host, port = portNum)
    }
    case m: Http.Bound => log.info("Bound received. EventServer is ready.")
    case m: Http.CommandFailed => log.error("Command failed.")
    case _ => log.error("Unknown message.")
  }
}


case class EventServerConfig(
  ip: String = "localhost",
  port: Int = 7070
)

object EventServer {
  def createEventServer(config: EventServerConfig) = {
    implicit val system = ActorSystem("EventServerSystem")

    val eventClient = Storage.getEventDataEvents
    val accessKeysClient = Storage.getMetaDataAccessKeys

    val serverActor = system.actorOf(
      Props(classOf[EventServerActor], eventClient, accessKeysClient),
      "EventServerActor")
    serverActor ! StartServer(config.ip, config.port)
    system.awaitTermination

  }
}


object Run {

  def main (args: Array[String]) {
    EventServer.createEventServer(EventServerConfig(
      ip = "localhost",
      port = 7070))
  }

}
