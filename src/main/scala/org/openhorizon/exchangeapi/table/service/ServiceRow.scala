package org.openhorizon.exchangeapi.table.service

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class ServiceRow(service: String,
                            orgid: String,
                            owner: String,
                            label: String,
                            description: String,
                            public: Boolean,
                            documentation: String,
                            url: String,
                            version: String,
                            arch: String,
                            sharable: String,
                            matchHardware: String,
                            requiredServices: String,
                            userInput: String,
                            deployment: String,
                            deploymentSignature: String,
                            clusterDeployment: String,
                            clusterDeploymentSignature: String,
                            imageStore: String,
                            lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toService: Service = {
    val mh: Map[String, Any] = if (matchHardware != "") read[Map[String, Any]](matchHardware) else Map[String, Any]()
    val rs: List[ServiceRef] = if (requiredServices != "") read[List[ServiceRef]](requiredServices) else List[ServiceRef]()
    
    val rs2: List[ServiceRef] = rs.map(sr => {
      val vr: Option[String] = if (sr.versionRange.isEmpty) sr.version else sr.versionRange
      ServiceRef(sr.url, sr.org, sr.version, vr, sr.arch)
    })
    
    val input: List[Map[String, String]] = if (userInput != "") read[List[Map[String, String]]](userInput) else List[Map[String, String]]()
    val p: Map[String, Any] = if (imageStore != "") read[Map[String, Any]](imageStore) else Map[String, Any]()
    new Service(owner, label, description, public, documentation, url, version, arch, sharable, mh, rs2, input, deployment, deploymentSignature, clusterDeployment, clusterDeploymentSignature, p, lastUpdated)
  }
  
  // update returns a DB action to update this row
  def update: DBIO[_] = (for {m <- ServicesTQ if m.service === service} yield m).update(this)
  
  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ServicesTQ += this
}
