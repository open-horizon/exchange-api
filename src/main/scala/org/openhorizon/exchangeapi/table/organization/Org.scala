package org.openhorizon.exchangeapi.table.organization

import org.json4s.{Formats, JValue}
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals


final case class Org(orgType: String,
                     label: String,
                     description: String,
                     lastUpdated: String,
                     tags: Option[Map[String, String]],
                     limits: OrgLimits,
                     heartbeatIntervals: NodeHeartbeatIntervals) {
  def this(tuple: (String, String, String, String, String, String, Option[JValue]))(implicit formats: Formats) =
    this(description = tuple._1,
         heartbeatIntervals =
           if (tuple._2 != "")
             read[NodeHeartbeatIntervals](tuple._2)
           else
             NodeHeartbeatIntervals(0, 0, 0),
         label = tuple._3,
         lastUpdated = tuple._4,
         limits =
           if (tuple._5 != "")
             read[OrgLimits](tuple._5)
           else
             OrgLimits(0),
         orgType = tuple._6,
         tags = tuple._7.flatMap(_.extractOpt[Map[String, String]]))
}
