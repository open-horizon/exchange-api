package org.openhorizon.exchangeapi.route.organization

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{OrgLimits, OrgsTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, ExchConfig, ExchMsg}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

final case class PatchOrgRequest(orgType: Option[String],
                                 label: Option[String],
                                 description: Option[String],
                                 tags: Option[Map[String, Option[String]]],
                                 limits: Option[OrgLimits],
                                 heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem(orgMaxNodes: Int): Option[String] = {
    val exchangeMaxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
    if (orgMaxNodes > exchangeMaxNodes) Some.apply(ExchMsg.translate("org.limits.cannot.be.over.exchange.limits", orgMaxNodes, exchangeMaxNodes))
    else None
  }
  /** Returns a tuple of the db action to update parts of the org, and the attribute name being updated. */
  def getDbUpdate(orgId: String)(implicit executionContext: ExecutionContext): (DBIO[_], String) = {
    import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.plainAPI._
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this org
    orgType match { case Some(ot) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.orgType, d.lastUpdated)).update((orgId, ot, lastUpdated)), "orgType"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.label, d.lastUpdated)).update((orgId, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.description, d.lastUpdated)).update((orgId, desc, lastUpdated)), "description"); case _ => ; }
    heartbeatIntervals match { case Some(hbIntervals) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.heartbeatIntervals, d.lastUpdated)).update((orgId, write(hbIntervals), lastUpdated)), "heartbeatIntervals"); case _ => ; }
    tags match { case Some(ts) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.tags, d.lastUpdated)).update((orgId, Some(ApiUtils.asJValue(ts)), lastUpdated)), "tags"); case _ => ; }
//    tags match {
//      case Some(ts) =>
//        val (deletes, updates) = ts.partition {
//          case (_, v) => v.isEmpty
//        }
//        val dbUpdates =
//          if (updates.isEmpty) Seq()
//          else Seq(sqlu"update orgs set tags = coalesce(tags, '{}'::jsonb) || ${ApiUtils.asJValue(updates)} where orgid = $orgId")
//
//        val dbDeletes =
//          for (tag <- deletes.keys.toSeq) yield {
//            sqlu"update orgs set tags = tags - $tag where orgid = $orgId"
//          }
//        val allChanges = dbUpdates ++ dbDeletes
//        return (DBIO.sequence(allChanges).map(counts => counts.sum), "tags")
//      case _ =>
//    }
    limits match { case Some(lim) => return ((for { d <- OrgsTQ if d.orgid === orgId } yield (d.orgid, d.limits, d.lastUpdated)).update((orgId, write(lim), lastUpdated)), "limits"); case _ => ; }
    (null, null)
  }
}
