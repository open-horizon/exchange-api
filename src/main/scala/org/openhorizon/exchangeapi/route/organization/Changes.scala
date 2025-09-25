package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{POST, Path}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, IAgbot, INode, Identity, Identity2, TOrg}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeOperation, ResourceChangeRow, ResourceChanges, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.ExchangeApi
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.time.{Instant, ZonedDateTime}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Changes extends JacksonSupport with AuthenticationSupport{
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  def buildResourceChangesResponse(@Parameter(hidden = true) inputList: scala.Seq[ResourceChangeRow],
                                   @Parameter(hidden = true) hitMaxRecords: Boolean,
                                   @Parameter(hidden = true) inputChangeId: Long,
                                   @Parameter(hidden = true) maxChangeIdOfTable: Long): ResourceChangesRespObject ={
    // Sort the rows based on the changeId. Default order is ascending, which is what we want
    logger.debug(s"POST /orgs/{organization}/changes - sorting ${inputList.size} rows")
    // val inputList = inputListUnsorted.sortBy(_.changeId)  // Note: we are doing the sorting here instead of in the db via sql, because the latter seems to use a lot of db cpu

    // fill in some values we can before processing
    val exchangeVersion: String = ExchangeApi.adminVersion()
    // set up needed variables
    val maxChangeIdInResponse: Long = inputList.last.changeId
    val changesMap: scala.collection.mutable.Map[String, ChangeEntry] = scala.collection.mutable.Map[String, ChangeEntry]() //using a Map allows us to avoid having a loop in a loop when searching the map for the resource id
    // fill in changesMap
    for (entry <- inputList) { // looping through every single ResourceChangeRow in inputList, given that we apply `.take(maxRecords)` in the query, this should never be over maxRecords, so no more need to break
      val resChange: ResourceChangesInnerObject = ResourceChangesInnerObject(entry.changeId, entry.lastUpdated.toString)
      changesMap.get(entry.orgId + "_" + entry.id + "_" + entry.resource) match { // using the map allows for better searching and entry
        case Some(change) =>
          // inputList is already sorted by changeId from the query so we know this change happened later
          change.addToResourceChanges(resChange) // add the changeId and lastUpdated to the list of recent changes
          change.setOperation(entry.operation) // update the most recent operation performed
        case None => // add the change to the changesMap
          val resChangeListBuffer: ListBuffer[ResourceChangesInnerObject] = ListBuffer[ResourceChangesInnerObject](resChange)
          changesMap.put(entry.orgId + "_" + entry.id + "_" + entry.resource, ChangeEntry(entry.orgId, entry.resource, entry.id, entry.operation, resChangeListBuffer))
      } // end of match
    } // end of for loop
    // now we have changesMap which is Map[String, ChangeEntry] we need to convert that to a List[ChangeEntry]
    val changesList: List[ChangeEntry] = changesMap.values.toList
    var maxChangeId = 0L
    if (hitMaxRecords) maxChangeId = maxChangeIdInResponse   // we hit the max records, so there are possibly value entries we are not returning, so the client needs to start here next time
    else if (maxChangeIdOfTable > 0) maxChangeId = maxChangeIdOfTable   // we got a valid max change id in the table, and we returned all relevant entries, so the client can start at the end of the table next time
    else maxChangeId = inputChangeId    // we didn't get a valid maxChangeIdInResponse or maxChangeIdOfTable, so just give the client back what they gave us
    ResourceChangesRespObject(changesList, maxChangeId, hitMaxRecords, exchangeVersion)
  }

  /* ====== POST /orgs/{organization}/changes ================================ */
  @POST
  @Path("orgs/{organization}/changes")
  @Operation(
    summary = "Returns recent changes in this org",
    description = "Returns all the recent resource changes within an org that the caller has permissions to view.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "changeId": 1234,
  "lastUpdated": "2019-05-14T16:34:36.295Z[UTC]",
  "maxRecords": 100,
  "orgList": ["", "", ""]
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[ResourceChangesRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "changes returned - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ResourceChangesRespObject])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
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
  def postChanges(@Parameter(hidden = true) identity: Identity2,
                  @Parameter(hidden = true) organization: String,
                  @Parameter(hidden = true) reqBody: ResourceChangesRequest): Route = {
    complete({
      // make sure callers obey maxRecords cap set in config, defaults is 10,000
      val maxRecordsCap: Int = Configuration.getConfig.getInt("api.resourceChanges.maxRecordsCap")
      logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - maxRecordsCap:                   $maxRecordsCap")
      
      val maxRecords: Int =
        if (maxRecordsCap < reqBody.maxRecords)
          maxRecordsCap
        else
          reqBody.maxRecords
      logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - maxRecords:                      $maxRecords")
      
      val orgList : List[String] =
        if (reqBody.orgList.isDefined)
          if (reqBody.orgList.get.contains(organization) ||
              reqBody.orgList.get.contains("*"))
            reqBody.orgList.get
          else
            reqBody.orgList.get ++ List(organization)
        else
          List(organization)
      logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - organizations to search:         ${orgList.toString()}")
      
      val orgSet : Set[String] = orgList.toSet
      
      val reqChangeId: Option[Long] =
        if (reqBody.changeId <= 0)
          None
        else
          Option(reqBody.changeId)
      logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - changeId to search:              $reqChangeId")
      
      // Convert strigified timestamp into a Timestamp
      val reqLastUpdate: Option[Instant] =
        (reqBody.lastUpdated, reqChangeId) match {
          case (Some(""), _) => None  // Empty timestamp
          case (None, _) => None      // Empty timestamp
          case (_, Some(_)) => None   // Some(ChangeId), take over timestamp
          case (_, None) => Option(ZonedDateTime.parse(reqBody.lastUpdated.get).toInstant.minusMillis(10000)) // Some(timestamp).  // Roll back time to catch any records that were added after we last queried. Timestamp only.
        }
      logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - timestamp to search:             $reqLastUpdate")
      
      val organizationRestriction: Option[Boolean] =
        if (!(identity.isMultiTenantAgbot ||
              identity.isSuperUser))
          Option(true)
        else
          None
      
      def allChanges(queryEarlierTimestamp: Option[Instant]): Query[ResourceChanges, ResourceChangeRow, Seq] =
        ResourceChangesTQ.filterOpt(organizationRestriction)((change, _) => (change.orgId === organization || change.public === "true"))
                         .filterOpt(if (queryEarlierTimestamp.isDefined) None else reqChangeId)((change, changeId) => change.changeId >= changeId)
                         .filterOpt(reqLastUpdate)((change, timestamp) => change.lastUpdated >= timestamp)
                         .filterOpt(queryEarlierTimestamp)((change, timestamp) => (change.changeId >= reqChangeId.get || change.lastUpdated >= timestamp))
      
      def changesWithAuth(queryEarlierTimestamp: Option[Instant]): PostgresProfile.api.Query[ResourceChanges, ResourceChangeRow, Seq] =
        identity.role match {
          case AuthRoles.Node =>
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - User Arch:                       Node")
            allChanges(queryEarlierTimestamp).filter(u => (u.category === "mgmtpolicy") || (u.category === "node" && u.id === identity.username) || (u.category === "service" || u.category === "org"))
          case AuthRoles.Agbot =>
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - User Arch:                       Agbot: " + identity.isMultiTenantAgbot)
            allChanges(queryEarlierTimestamp).filterIf(identity.isMultiTenantAgbot && !(orgSet.contains("*") || orgSet.contains("")))(u => (u.orgId inSet orgSet) || ((u.resource === "org") && (u.operation === ResChangeOperation.CREATED.toString)))
                                             .filterNot(_.resource === "nodemsgs")
                                             .filterNot(_.resource === "nodestatus")
                                             .filterNot(u => u.resource === "nodeagreements" && u.operation === ResChangeOperation.CREATEDMODIFIED.toString)
                                             .filterNot(u => u.resource === "agbotagreements" && u.operation === ResChangeOperation.CREATEDMODIFIED.toString)
          case _ =>
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - User Arch:                       User")
            allChanges(queryEarlierTimestamp)
        }
      
      val changes: DBIOAction[(Seq[ResourceChangeRow], Option[Long]), NoStream, Effect.Write with Effect with Effect.Read] =
        for {
          /*
           * Attempt to convert the incoming ChangeId from the request to a timestamp. This can only work if
           * the record tied to the given ChangeId still exists in the database and has not been cleaned
           * up by the change record cleaning actor. This is a mitigation not a fix, nor a solution.
           */
          changeIdTimestamp <-
            if (reqChangeId.isDefined)
              Compiled(ResourceChangesTQ.filter(_.changeId === reqChangeId.get)
                                        .take(1)
                                        .map(_.lastUpdated))
                .result
                .headOption
            else
              DBIO.successful(None)
          
          _ = Future { logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Timestamp of changeId:           ${changeIdTimestamp.getOrElse("Change record no longer exists")}") }
          
          queryEarlierTimestamp =
            try
              Option(changeIdTimestamp.get.minusSeconds(system.settings.config.getInt("api.resourceChanges.contentionDuration")))
            catch { case _: Throwable => None }
          
          _ = Future { logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Converted timestamp to query on: ${changeIdTimestamp.getOrElse("None")}") }
          
          resourceUpdated <-
            if (identity.isNode)
              Compiled(NodesTQ.getLastHeartbeat(identity.resource)).update(Option(ApiTime.nowUTC))
            else if (identity.isAgbot)
              Compiled(AgbotsTQ.getLastHeartbeat(identity.resource)).update(ApiTime.nowUTC)
            else
              DBIO.successful((1))
          
          _ <-
            if (resourceUpdated == 1)
              DBIO.successful(())
            else
              DBIO.failed(new IllegalCallerException())
          
          // Shortcut to grabbing the last allocated ChangeId.
          currentChange <-
            sql"SELECT last_value FROM public.resourcechanges_changeid_seq;".as[(Long)].headOption
          
          changes <-
            Compiled(changesWithAuth(queryEarlierTimestamp).sortBy(_.changeId.asc.nullsFirst).take(maxRecords)).result
        } yield((changes, currentChange))
      
      implicit val formats: Formats = DefaultFormats
      db.run(changes.transactionally.asTry).map({
        case Success(result) =>
          if (result._1.nonEmpty) {
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - changes:                         ${result._1.length}")
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - currentChange:                   ${result._2}")
            (HttpCode.POST_OK, buildResourceChangesResponse(inputList = result._1,
              hitMaxRecords = (result._1.sizeIs == maxRecords),
              inputChangeId = reqBody.changeId,
              maxChangeIdOfTable = result._2.getOrElse(0)))
          }
          else {
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - changes:                         0")
            logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - currentChange:                   ${result._2}")
            (HttpCode.POST_OK, ResourceChangesRespObject(changes = List[ChangeEntry](),
              mostRecentChangeId = result._2.getOrElse(0),
              hitMaxRecords = false,
              exchangeVersion = ExchangeApi.adminVersion()))
          }
        case Failure(exception: IllegalCallerException) =>
          Future { logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.or.agbot.not.found", identity.resource))))}") }
          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.or.agbot.not.found", identity.resource)))
        case Failure(exception: org.postgresql.util.PSQLException) =>
          Future { logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("invalid.input.message", exception.getMessage)))}") }
          ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("invalid.input.message", exception.getMessage))
        case Failure(exception) =>
          Future { logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.BAD_INPUT, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage))))}") }
          (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage)))
      })
    })
  }
  
  def changes(identity: Identity2): Route =
    path("orgs" / Segment / "changes") {
      organization =>
        post {
          exchAuth(TOrg(organization), Access.READ, validIdentity = identity) {
            _ =>
              logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
              entity(as[ResourceChangesRequest]) {
                reqBody =>
                  logger.debug(s"POST /orgs/${organization}/changes - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Request { changeId:${reqBody.changeId}, lastUpdated:${reqBody.lastUpdated.getOrElse("NONE")}, maxRecords:${reqBody.maxRecords}, orgList:${reqBody.orgList.getOrElse(List.empty[String])} }")
                  validateWithMsg(reqBody.getAnyProblem) {
                    postChanges(identity, organization, reqBody)
                  }
              }
          }
        }
    }
}
