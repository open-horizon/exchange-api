package org.openhorizon.exchangeapi.route.node

import org.json4s.jackson.Serialization.{read, write}
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.ResourceNotFoundException
import org.openhorizon.exchangeapi.table.node.{NodesTQ, RegService}
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.util.matching.Regex

/** Input body for POST /orgs/{organization}/nodes/{node}/services_configstate */
final case class PostNodeConfigStateRequest(org: String, url: String, configState: String, version: Option[String]) {
  require(org!=null && url!=null && configState!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  //def logger: Logger    // get access to the logger object in ExchangeApiApp

  def getAnyProblem: Option[String] = {
    if (configState != "suspended" && configState != "active") Option(ExchMsg.translate("configstate.must.be.suspended.or.active"))
    else if (org == "" && (url != "" || version.getOrElse("") != "")) Option(ExchMsg.translate("services.configstate.org.not.specified"))
    else if (url == "" && version.getOrElse("") != "") Option(ExchMsg.translate("services.configstate.url.not.specified"))
    else None
  }

  // Match registered service urls (which are org/url) to the input org and url
  def isMatch(compositeUrl: String): Boolean = {
    val reg: Regex = """^(\S+?)/(\S+)$""".r
    val (comporg, compurl) = compositeUrl match {
      case reg(o,u) => (o, u)
      case _ => return false   //todo: halt(StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("configstate.must.be.suspended.or.active", compositeUrl)))
    }
    (org, url) match {
      case ("","") => true
      case ("",u) => compurl == u
      case (o,"") => comporg == o
      case (o,u) => comporg == o && compurl == u
    }
  }

  // Given the existing list of registered svcs in the db for this node, determine the db update necessary to apply the new configState
  def getDbUpdate(regServices: String, id: String): DBIO[_] = {
    if (regServices == "") return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))
    val regSvcs: Seq[RegService] = read[List[RegService]](regServices)
    if (regSvcs.isEmpty) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))

    // Copy the list of required svcs, changing configState wherever it applies
    var matchingSvcFound = false
    val newRegSvcs: Seq[RegService] = regSvcs.map({ rs =>
      if (isMatch(rs.url)) {
        matchingSvcFound = true   // warning: intentional side effect (didnt know how else to do it)
        // Match the version, either the version sent in the request body matches exactly or the wildcard version ("") was sent in
        val versionCheck: Boolean = (version.getOrElse("") == rs.version.getOrElse("")) || (version.getOrElse("") == "")
        val newConfigState: Option[String] = if (configState != rs.configState.getOrElse("") && versionCheck) Option(configState) else rs.configState
        RegService(rs.url,rs.numAgreements, newConfigState, rs.policy, rs.properties, rs.version)
      }
      else rs
    })
    // this check is not ok, because we should not return NOT_FOUND if we find matching svc but their configState is already set the requested value
    //if (newRegSvcs.sameElements(regSvcs)) halt(StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "did not find any registeredServices that matched the given org and url criteria."))
    if (!matchingSvcFound) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("did.not.find.registered.services")))
    if (newRegSvcs == regSvcs) {
      return DBIO.successful(1)    // all the configStates were already set correctly, so nothing to do
    }

    // Convert from struct back to string and return db action to update that
    val newRegSvcsString: String = write(newRegSvcs)
    val nowTime: String = ApiTime.nowUTC
    (for { d <- NodesTQ if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat,d.lastUpdated)).update((id, newRegSvcsString, Option(nowTime), nowTime))
  }
}
