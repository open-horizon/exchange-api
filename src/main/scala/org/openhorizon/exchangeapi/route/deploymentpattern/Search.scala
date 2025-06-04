package org.openhorizon.exchangeapi.route.deploymentpattern

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.{PatternNodeResponse, PostPatternSearchResponse}
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServices, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/patterns/{pattern}/search")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
trait Search extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ======== POST /org/{organization}/patterns/{deploymentPattern}/search ========================
  @POST
  @Operation(
    summary = "Returns matching nodes of a particular pattern",
    description = "Returns the matching nodes that are using this pattern and do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "arch": "arm",
  "nodeOrgids": [ "org1", "org2", "..." ],
  "secondsStale": 60,
  "serviceUrl": "myorg/mydomain.com.sdr"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPatternSearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostPatternSearchResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  def postSearchNode(@Parameter(hidden = true) deploymentPattern: String,
                     @Parameter(hidden = true) identity: Identity2,
                     @Parameter(hidden = true) organization: String,
                     @Parameter(hidden = true) resource: String): Route =
    entity(as[PostPatternSearchRequest]) {
      reqBody =>
        logger.debug(s"POST /org/${organization}/patterns/${deploymentPattern}/search - By ${identity.resource}:${identity.role}")
        
        validateWithMsg(if(!(reqBody.secondsStale.isEmpty || !(reqBody.secondsStale.get < 0)) && !reqBody.serviceUrl.isEmpty) Some(ExchMsg.translate("bad.input")) else None) {
          complete({
            val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(organization)).toSet
  //          logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search criteria: "+reqBody.toString)
            val searchSvcUrl: String = reqBody.serviceUrl   // this now is a composite value (org/url), but plain url is supported for backward compat
            val selectedServiceArch: Option[String] = reqBody.arch
            /*
              Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
              In english, the join gets: n.id, n.msgEndPoint, n.publicKey, a.serviceUrl, a.state
              The filters are: n is in the given list of node orgs, n.pattern==ourpattern, the node is not stale, there is an agreement for this node (the filter a.state=="" is applied later in our code below)
              Then we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
              Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
            */
            //val oldestTime = if (reqBody.secondsStale > 0) ApiTime.pastUTC(reqBody.secondsStale) else ApiTime.beginningUTC
  
            db.run(PatternsTQ.getServices(resource).result.flatMap({ list =>
  //            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search getServices size: "+list.size)
  //            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search: looking for '"+searchSvcUrl+"', searching getServices: "+list.toString())
              if (list.nonEmpty) {
                val services: Seq[PServices] = PatternsTQ.getServicesFromString(list.head)    // we should have found only 1 pattern services string, now parse it to get service list
                var found = false
                var svcArch = ""
                breakable { for ( svc <- services) {
                  if (svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl || svc.serviceUrl == searchSvcUrl) {
                    found = true
                    svcArch = svc.serviceArch
                    break()
                  }
                } }
                val archList = new ListBuffer[String]()
                val secondsStaleOpt: Option[Int] =
                  if(reqBody.secondsStale.isDefined && reqBody.secondsStale.get.equals(0))
                    None
                  else
                    reqBody.secondsStale
                
                for ( svc <- services) {
                  if(svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl){
                    archList += svc.serviceArch
                  }
                }
                archList += svcArch
                archList += ""
                archList += "*"
                val archSet: Set[String] = archList.toSet
                if (found) {
                  /*     Build the node query
                   * 1 - if the caller specified a non-wildcard arch in the body, that trumps everything, so filter on that arch
                   * 2 - else if the caller or any service specified a blank/wildcard arch, then don't filter on arch at all
                   * 3 - else filter on the arches in the services
                   */
                  val optArchSet: Option[Set[String]] =
                    if(selectedServiceArch.isDefined &&
                       !(selectedServiceArch.contains("*") ||
                         selectedServiceArch.contains("")))
                      Some(Set(selectedServiceArch.get))
                    else if (((archSet("") ||
                               archSet("*")) &&
                              selectedServiceArch.isEmpty) ||
                             selectedServiceArch.contains("*") ||
                             selectedServiceArch.contains(""))
                      None
                    else
                      Some(archSet)
                  
                  NodesTQ
                    .filterOpt(optArchSet)((node, archs) => node.arch inSet(archs))
                    .filterOpt(secondsStaleOpt)((node, secondsStale) => !(node.lastHeartbeat < ApiTime.pastUTC(secondsStale)))
                    .filter(_.lastHeartbeat.isDefined)
                    .filter(_.orgid inSet(nodeOrgids))
                    .filter(_.pattern === resource)
                    .filter(_.publicKey =!= "")
                    .map(node => (node.id, node.nodeType, node.publicKey))
                  .joinLeft(NodeAgreementsTQ
                              .filter(_.agrSvcUrl === searchSvcUrl)
                               .map(agreement => (agreement.agrSvcUrl, agreement.nodeId, agreement.state)))
                    .on((node, agreement) => node._1 === agreement._2)
                  .filter ({
                    case (node, agreement) =>
                     (agreement.map(_._2).isEmpty ||
                      agreement.map(_._1).getOrElse("") === "" ||
                      agreement.map(_._3).getOrElse("") === "")
                  })
                  // .sortBy(r => (r._1._1.asc, r._2.getOrElse(("", "", ""))._1.asc.nullsFirst))
                  .map(r => (r._1._1, r._1._2, r._1._3)).result.map(List[(String, String, String)]).asTry
                }
                //        else DBIO.failed(new Throwable("the serviceUrl '"+searchSvcUrl+"' specified in search body does not exist in pattern '"+compositePat+"'")).asTry
                else DBIO.failed(new Throwable(ExchMsg.translate("service.not.in.pattern", searchSvcUrl, resource))).asTry
              }
              else DBIO.failed(new Throwable(ExchMsg.translate("pattern.id.not.found", resource))).asTry
            })).map({
              case Success(list) =>
  //              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + "/search result size: " + list.size)
                if (list.nonEmpty) {
                  (HttpCode.POST_OK,
                   PostPatternSearchResponse(list
                                               .map(
                                                 node =>
                                                   PatternNodeResponse(node._1,
                                                                       node._2 match {
                                                                         case "" => NodeType.DEVICE.toString
                                                                         case _ => node._2
                                                                       },
                                                                       node._3)),
                                             0))
                }
                else {
                  (HttpCode.NOT_FOUND, PostPatternSearchResponse(List[PatternNodeResponse](), 0))
                }
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
            })
          })
        }
    }
  
  def searchNode(identity: Identity2): Route =
    path("orgs" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns") / Segment / "search") {
      (organization,
       deploymentPattern) =>
        val resource: String = OrgAndId(organization, deploymentPattern).toString
        
        post {
          exchAuth(TNode(OrgAndId(organization,"*").toString), Access.READ, validIdentity = identity) {
            _ =>
              postSearchNode(deploymentPattern, identity, organization, resource)
          }
        }
    }
}
