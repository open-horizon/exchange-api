package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.{Agbot, AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/agbots")
trait AgreementBots extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /orgs/{organization}/agbots ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all Agreement Bots",
             description = "Returns all Agreement Bots (AgBots). Can be run by any User.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include AgBots with this identifier (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
                     new Parameter(name = "name", in = ParameterIn.QUERY, required = false, description = "Filter results to only include AgBots with this name (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
                     new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include AgBots with this owner (can include '%' for wildcard - the URL encoding for '%' is '%25')")),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
  "agbots": {
    "orgid/agbotname": {
      "token": "string",
      "name": "string",
      "owner": "string",
      "msgEndPoint": "",
      "lastHeartbeat": "2020-05-27T19:01:10.713Z[UTC]",
      "publicKey": "string"
    }
  },
  "lastIndex": 0
}""")),
                                                                   mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[GetAgbotsResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def getAgreementBots(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) organization: String): Route = {
    parameter("idfilter".?, "name".?, "owner".?) {
      (idfilter, name, owner) =>
        logger.debug(s"GET /orgs/$organization/agbots - By ${identity.resource}:${identity.role}")
        
        val getAgbots =
          for {
            agbots <-
              AgbotsTQ.filter(_.orgid === organization)
                      .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot) (agreement_bots => agreement_bots.orgid === identity.organization || agreement_bots.orgid === "IBM")
                      .filterOpt(idfilter)((agbot, id) =>
                        if (id.contains("%"))
                          agbot.id like id
                        else
                          agbot.id === id)
                      .filterOpt(name)((agbot, name) =>
                        if (name.contains("%"))
                          agbot.name like name
                        else
                          agbot.name === name)
                      .join(UsersTQ.map(user => (user.organization, user.user, user.username)))
                      .on(_.owner === _._2)
                      .filterOpt(owner)((agbot, owner) =>
                        if (name.contains("%"))
                          (agbot._2._1 ++ "/" ++ agbot._2._3) like owner
                        else
                          (agbot._2._1 ++ "/" ++ agbot._2._3) === owner)
                      .sortBy(agbot => (agbot._1.orgid.asc, agbot._1.id.asc))
                      .map(agbot =>
                            (agbot._1.id,
                             agbot._1.lastHeartbeat,
                             agbot._1.msgEndPoint,
                             agbot._1.name,
                             agbot._1.orgid,
                             (agbot._2._1 ++ "/" ++ agbot._2._3),
                             agbot._1.publicKey,
                             "***************"))
          } yield agbots.mapTo[Agbot]
        
        complete({
          db.run(Compiled(getAgbots).result.transactionally.asTry).map({
            case Success(agbots) =>
                logger.debug(s"GET /orgs/$organization/agbots result size: ${agbots.size}")
                val agbotMap: Map[String, Agbot] = agbots.map(agbot => agbot.id -> agbot).toMap
              
              ((if (agbotMap.isEmpty) StatusCodes.NotFound else StatusCodes.OK), GetAgbotsResponse(agbotMap, 0))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("unknown.error.invalid.creds")))
            })
        })
    }
  }
  
  
  def agreementBots(identity: Identity2): Route =
    path("orgs" / Segment / "agbots") {
      organization =>
        get {
          exchAuth(TAgbot(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
            _ =>
              getAgreementBots(identity, organization)
          }
        }
    }
}
