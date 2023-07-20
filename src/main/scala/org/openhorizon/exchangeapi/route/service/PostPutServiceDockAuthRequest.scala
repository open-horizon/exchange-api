package org.openhorizon.exchangeapi.route.service

import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.service.dockerauth.{ServiceDockAuthRow, ServiceDockAuths, ServiceDockAuthsTQ}
import slick.jdbc.PostgresProfile.api._

import scala.util.{Failure, Success, Try}

final case class PostPutServiceDockAuthRequest(registry: String,
                                               username: Option[String],
                                               token: String) {
  def toServiceDockAuthRow(serviceId: String, dockAuthId: Int): ServiceDockAuthRow = ServiceDockAuthRow(dockAuthId, serviceId, registry, username.getOrElse("token"), token, ApiTime.nowUTC)
  def getAnyProblem(dockauthIdAsStr: Option[String]): Option[String] = {
    if (dockauthIdAsStr.isEmpty) None
    else Try(dockauthIdAsStr.get.toInt) match {
      case Success(_) => None
      case Failure(t) => Option("dockauthid must be an integer: " + t.getMessage)
    }
  }
  def getDupDockAuth(serviceId: String): Query[ServiceDockAuths, ServiceDockAuthRow, Seq] = ServiceDockAuthsTQ.getDupDockAuth(serviceId, registry, username.getOrElse("token"), token)
}
