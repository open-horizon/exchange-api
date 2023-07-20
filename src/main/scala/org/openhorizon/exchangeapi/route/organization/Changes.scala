package org.openhorizon.exchangeapi.route.organization

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{POST, Path}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeOperation, ResourceChangeRow, ResourceChanges, ResourceChangesTQ}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, AuthenticationSupport, ExchConfig, ExchMsg, ExchangeApi, ExchangePosgtresErrorHandling, HttpCode, IAgbot, INode, Identity, TOrg}
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Changes extends JacksonSupport with AuthenticationSupport{
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  def buildResourceChangesResponse(inputList: scala.Seq[ResourceChangeRow],
                                   hitMaxRecords: Boolean,
                                   inputChangeId: Long,
                                   maxChangeIdOfTable: Long): ResourceChangesRespObject ={
    // Sort the rows based on the changeId. Default order is ascending, which is what we want
    logger.info(s"POST /orgs/{orgid}/changes sorting ${inputList.size} rows")
    // val inputList = inputListUnsorted.sortBy(_.changeId)  // Note: we are doing the sorting here instead of in the db via sql, because the latter seems to use a lot of db cpu

    // fill in some values we can before processing
    val exchangeVersion: String = ExchangeApi.adminVersion()
    // set up needed variables
    val maxChangeIdInResponse: Long = inputList.last.changeId
    val changesMap: scala.collection.mutable.Map[String, ChangeEntry] = scala.collection.mutable.Map[String, ChangeEntry]() //using a Map allows us to avoid having a loop in a loop when searching the map for the resource id
    // fill in changesMap
    for (entry <- inputList) { // looping through every single ResourceChangeRow in inputList, given that we apply `.take(maxRecords)` in the query, this should never be over maxRecords, so no more need to break
      val resChange: ResourceChangesInnerObject = ResourceChangesInnerObject(entry.changeId, ApiTime.fixFormatting(entry.lastUpdated.toString))
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

  /* ====== POST /orgs/{orgid}/changes ================================ */
  @POST
  @Path("orgs/{orgid}/changes")
  @Operation(
    summary = "Returns recent changes in this org",
    description = "Returns all the recent resource changes within an org that the caller has permissions to view.",
    parameters = Array(
      new Parameter(
        name = "orgid",
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
  def postChanges(identity: Identity,
                  organization: String,
                  reqBody: ResourceChangesRequest): Route =
    complete({
      logger.debug(s"Doing POST /orgs/$organization/changes - identity:                 ${identity.identityString}")
      // make sure callers obey maxRecords cap set in config, defaults is 10,000
      val maxRecordsCap: Int = ExchConfig.getInt("api.resourceChanges.maxRecordsCap")
      logger.debug(s"Doing POST /orgs/$organization/changes - maxRecordsCap:            $maxRecordsCap")
      
      val maxRecords: Int =
        if (maxRecordsCap < reqBody.maxRecords)
          maxRecordsCap
        else
          reqBody.maxRecords
      logger.debug(s"Doing POST /orgs/$organization/changes - maxRecords:               $maxRecords")
      
      val orgList : List[String] =
        if (reqBody.orgList.isDefined)
          if (reqBody.orgList.get.contains(organization) ||
              reqBody.orgList.get.contains("*"))
            reqBody.orgList.get
          else
            reqBody.orgList.get ++ List(organization)
        else
          List(organization)
      logger.debug(s"Doing POST /orgs/$organization/changes - organizations to search:  ${orgList.toString()}")
      
      val orgSet : Set[String] = orgList.toSet
      
      val reqChangeId: Option[Long] =
        if (reqBody.changeId <= 0)
          None
        else
          Option(reqBody.changeId)
      logger.debug(s"Doing POST /orgs/$organization/changes - changeId to search:       $reqChangeId")
      
      // Convert strigified timestamp into a Timestamp
      val reqLastUpdate: Option[java.sql.Timestamp] =
        (reqBody.lastUpdated, reqChangeId) match {
          case (Some(""), _) => None  // Empty timestamp
          case (None, _) => None      // Empty timestamp
          case (_, Some(_)) => None   // Some(ChangeId), take over timestamp
          case (_, None) => Option(java.sql.Timestamp.from(ZonedDateTime.parse(reqBody.lastUpdated.get).toInstant))  // Some(timestamp)
        }
      logger.debug(s"Doing POST /orgs/$organization/changes - timestamp to search:      $reqLastUpdate")
      
      val organizationRestriction: Option[Boolean] =
        if (!(identity.isMultiTenantAgbot ||
              identity.isSuperUser))
          Option(true)
        else
          None
      
      val allChanges: Query[ResourceChanges, ResourceChangeRow, Seq] =
        ResourceChangesTQ.filterOpt(organizationRestriction)((change, _) => (change.orgId === organization || change.public === "true"))
                         .filterOpt(reqChangeId)((change, changeId) => change.changeId >= changeId)
                         .filterOpt(reqLastUpdate)((change, timestamp) => change.lastUpdated >= timestamp)
      
      val changesWithAuth: PostgresProfile.api.Query[ResourceChanges, ResourceChangeRow, Seq] =
        identity match {
          case _: INode =>
            logger.debug(s"Doing POST /orgs/$organization/changes - User Arch:                Node")
            allChanges.filter(u => (u.category === "mgmtpolicy") || (u.category === "node" && u.id === identity.getIdentity) || (u.category === "service" || u.category === "org"))
          case _: IAgbot =>
            logger.debug(s"Doing POST /orgs/$organization/changes - User Arch:                Agbot: " + identity.isMultiTenantAgbot)
            allChanges.filterIf(identity.isMultiTenantAgbot && !(orgSet.contains("*") || orgSet.contains("")))(u => (u.orgId inSet orgSet) || ((u.resource === "org") && (u.operation === ResChangeOperation.CREATED.toString)))
                      .filterNot(_.resource === "nodemsgs")
                      .filterNot(_.resource === "nodestatus")
                      .filterNot(u => u.resource === "nodeagreements" && u.operation === ResChangeOperation.CREATEDMODIFIED.toString)
                      .filterNot(u => u.resource === "agbotagreements" && u.operation === ResChangeOperation.CREATEDMODIFIED.toString)
          case _ =>
            logger.debug(s"Doing POST /orgs/$organization/changes - User Arch:                User")
            allChanges
        }
      
      val changes: DBIOAction[(Seq[ResourceChangeRow], Option[Long]), NoStream, Effect.Write with Effect with Effect.Read] =
        for {
          resourceUpdated <-
            identity match {
              case _: INode =>
                Compiled(NodesTQ.getLastHeartbeat(identity.identityString)).update(Option(ApiTime.nowUTC))
              case _: IAgbot =>
                Compiled(AgbotsTQ.getLastHeartbeat(identity.identityString)).update(ApiTime.nowUTC)
              case _ =>
                DBIO.successful((1))
            }
          
          _ <-
            if (resourceUpdated == 1)
              DBIO.successful(())
            else
              DBIO.failed(new IllegalCallerException())
          
          // Shortcut to grabbing the last allocated ChangeId.
          currentChange <-
            sql"SELECT last_value FROM public.resourcechanges_changeid_seq;".as[(Long)].headOption
          
          changes <-
            Compiled(changesWithAuth.sortBy(_.changeId.asc.nullsFirst).take(maxRecords)).result
        } yield((changes, currentChange))
      
      db.run(changes.transactionally.asTry).map({
        case Success(result) =>
          if (result._1.nonEmpty) {
            logger.debug(s"Doing POST /orgs/$organization/changes - changes:                  ${result._1.length}")
            logger.debug(s"Doing POST /orgs/$organization/changes - currentChange:            ${result._2}")
            (HttpCode.POST_OK, buildResourceChangesResponse(inputList = result._1,
              hitMaxRecords = (result._1.sizeIs == maxRecords),
              inputChangeId = reqBody.changeId,
              maxChangeIdOfTable = result._2.getOrElse(0)))
          }
          else {
            logger.debug(s"Doing POST /orgs/$organization/changes - changes:                  0")
            logger.debug(s"Doing POST /orgs/$organization/changes - currentChange:            ${result._2}")
            (HttpCode.POST_OK, ResourceChangesRespObject(changes = List[ChangeEntry](),
              mostRecentChangeId = result._2.getOrElse(0),
              hitMaxRecords = false,
              exchangeVersion = ExchangeApi.adminVersion()))
          }
        case Failure(t: IllegalCallerException) =>
          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.or.agbot.not.found", identity.getIdentity)))
        case Failure(t: org.postgresql.util.PSQLException) =>
          ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
        case Failure(t) =>
          (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
      })
    })
  
  def changes: Route =
    path("orgs" / Segment / "changes") {
      organization =>
        post {
          exchAuth(TOrg(organization), Access.READ) {
            identity =>
              logger.debug(s"Doing POST /orgs/$organization/changes")
              entity(as[ResourceChangesRequest]) {
                reqBody =>
                  validateWithMsg(reqBody.getAnyProblem) {
                    postChanges(identity, organization, reqBody)
                  }
              }
          }
        }
    }
}
