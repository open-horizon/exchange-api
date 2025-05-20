package org.openhorizon.exchangeapi.route.service

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.service.{ServiceRef, ServiceRow, ServicesTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg, Version}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.net.{MalformedURLException, URL}
import java.util.UUID
import scala.collection.mutable.ListBuffer

final case class PostPutServiceRequest(label: String,
                                       description: Option[String],
                                       public: Boolean,
                                       documentation: Option[String],
                                       url: String,
                                       version: String,
                                       arch: String,
                                       sharable: String,
                                       matchHardware: Option[Map[String,Any]],
                                       requiredServices: Option[List[ServiceRef]],
                                       userInput: Option[List[Map[String,String]]],
                                       deployment: Option[String],
                                       deploymentSignature: Option[String],
                                       clusterDeployment: Option[String],
                                       clusterDeploymentSignature: Option[String],
                                       imageStore: Option[Map[String,Any]]) {
  require(label!=null && url!=null && version!=null && arch!=null && sharable!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(orgid: String, serviceId: String): Option[String] = {

    // Ensure that the documentation field is a valid URL
    if (documentation.getOrElse("") != "") {
      try { new URL(documentation.getOrElse("")) }
      catch { case _: MalformedURLException => return Option(ExchMsg.translate("documentation.field.not.valid.url")) }
    }

    if (!Version(version).isValid) return Option(ExchMsg.translate("version.not.valid.format", version))
    if (arch == "") return Option(ExchMsg.translate("arch.cannot.be.empty"))

    // We enforce that the attributes equal the existing id for PUT, because even if they change the attribute, the id would not get updated correctly
    if (serviceId != null && serviceId != "" && formId(orgid) != serviceId) return Option(ExchMsg.translate("service.id.does.not.match"))

    val allSharableVals: Set[String] = SharableVals.values.map(_.toString)
    if (sharable == "" || !allSharableVals.contains(sharable)) return Option(ExchMsg.translate("invalid.sharable.value", sharable))

    // Check for requiring a service that is a different arch than this service
    for (rs <- requiredServices.getOrElse(List())) {
      if(rs.versionRange.isEmpty && rs.version.isEmpty) return Option(ExchMsg.translate("no.version.range.in.req.service", rs.url))
      if (rs.arch != arch) return Option(ExchMsg.translate("req.service.has.wrong.arch", rs.url, rs.arch, arch))
    }

    // Check that it is signed
    //if (deployment.getOrElse("") == "" && clusterDeployment.getOrElse("") == "") return Some(ExchMsg.translate("service.no.deployment"))  // <- not forcing this
    if (deployment.getOrElse("") != "" && deploymentSignature.getOrElse("") == "") return Option(ExchMsg.translate("service.def.not.signed"))
    if (clusterDeployment.getOrElse("") != "" && clusterDeploymentSignature.getOrElse("") == "") return Option(ExchMsg.translate("service.def.not.signed"))
    None
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: DBIO[Vector[Int]] = {
    if (requiredServices.isEmpty || requiredServices.get.isEmpty) return DBIO.successful(Vector())
    val actions: ListBuffer[DBIO[Int]] = ListBuffer[DBIO[Int]]()
    for (m <- requiredServices.get) {
      val finalVersionRange: String = if(m.versionRange.isEmpty) m.version.getOrElse("") else m.versionRange.getOrElse("")
      val svcId: String = ServicesTQ.formId(m.org, m.url, finalVersionRange, m.arch)     // need to wildcard version, because it is an osgi version range
      actions += ServicesTQ.getService(svcId).length.result
    }
    DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence because that returns query values
  }

  def formId(orgid: String): String = ServicesTQ.formId(orgid, url, version, arch)

  def toServiceRow(service: String, orgid: String, owner: UUID): ServiceRow = ServiceRow(service, orgid, owner, label, description.getOrElse(label), public, documentation.getOrElse(""), url, version, arch, sharable, write(matchHardware), write(requiredServices), write(userInput), deployment.getOrElse(""), deploymentSignature.getOrElse(""), clusterDeployment.getOrElse(""), clusterDeploymentSignature.getOrElse(""), write(imageStore), ApiTime.nowUTC)
}
