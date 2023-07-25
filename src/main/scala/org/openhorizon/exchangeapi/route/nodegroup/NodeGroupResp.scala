package org.openhorizon.exchangeapi.route.nodegroup

final case class NodeGroupResp(name: String,
                               description: String = "",
                               members: Seq[String],
                               admin: Boolean,
                               lastUpdated: String)
