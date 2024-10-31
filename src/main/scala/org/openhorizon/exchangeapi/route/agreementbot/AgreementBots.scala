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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.{Agbot, AgbotsTQ}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

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
  def getAgreementBots(@Parameter(hidden = true) identity: Identity,
                       @Parameter(hidden = true) organization: String): Route = {
    parameter("idfilter".?, "name".?, "owner".?) {
      (idfilter, name, owner) =>
        logger.debug(s"Doing GET /orgs/$organization/agbots")
        complete({
          logger.debug(s"GET /orgs/$organization/agbots identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
          var q = AgbotsTQ.getAllAgbots(organization)
          idfilter.foreach(
            id => {
              if (id.contains("%"))
                q = q.filter(_.id like id)
              else
                q = q.filter(_.id === id)
            })
          name.foreach(
            name => {
              if (name.contains("%"))
                q = q.filter(_.name like name)
              else
                q = q.filter(_.name === name)
            })
          owner.foreach(
            owner => {
              if (owner.contains("%"))
                q = q.filter(_.owner like owner)
              else
                q = q.filter(_.owner === owner)
            })
          db.run(q.result)
            .map({
              list =>
                logger.debug(s"GET /orgs/$organization/agbots result size: ${list.size}")
                val agbots: Map[String, Agbot] = list.map(e => e.id -> e.toAgbot(identity.isSuperUser)).toMap
                val code: StatusCode =
                  if (agbots.nonEmpty)
                    StatusCodes.OK
                  else
                    StatusCodes.NotFound
                (code, GetAgbotsResponse(agbots, 0))
            })
        })
    }
  }
  
  
  val agreementBots: Route =
    path("orgs" / Segment / "agbots") {
      organization =>
        get {
          exchAuth(TAgbot(OrgAndId(organization, "*").toString), Access.READ) {
            identity =>
              getAgreementBots(identity, organization)
          }
        }
    }
}
