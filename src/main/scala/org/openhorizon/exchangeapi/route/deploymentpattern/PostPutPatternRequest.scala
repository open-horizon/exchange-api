package org.openhorizon.exchangeapi.route.deploymentpattern

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService, PServices, PatternRow, PatternsTQ}
import org.openhorizon.exchangeapi.table.service.ServiceRef2
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchConfig, ExchMsg}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PostPutPatternRequest(label: String,
                                       description: Option[String],
                                       public: Option[Boolean],
                                       services: List[PServices],
                                       userInput: Option[List[OneUserInputService]],
                                       secretBinding: Option[List[OneSecretBindingService]],
                                       agreementProtocols: Option[List[Map[String,String]]],
                                       clusterNamespace: Option[String] = None) {
  require(label!=null && services!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if(services.isEmpty) return Option(ExchMsg.translate("no.services.defined.in.pattern"))
    PatternUtils.validatePatternServices(services)
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { PatternsTQ.validateServiceIds(services, userInput.getOrElse(List())) }

  // Note: write() handles correctly the case where the optional fields are None.
  def toPatternRow(pattern: String, orgid: String, owner: String): PatternRow = {
    // The nodeHealth field is optional, so fill in a default in each element of services if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
    val agrChkDefault: Int = ExchConfig.getInt("api.defaults.pattern.check_agreement_status")
    val agreementProtocols2: Option[List[Map[String, String]]] = agreementProtocols.orElse(Option(List(Map("name" -> "Basic"))))
    val hbDefault: Int = ExchConfig.getInt("api.defaults.pattern.missing_heartbeat_interval")
    val services2: Seq[PServices] =
      if (services.nonEmpty) {
        services.map({
          s =>
            val nodeHealth2: Option[Map[String, Int]] = s.nodeHealth.orElse(Option(Map("missing_heartbeat_interval" -> hbDefault, "check_agreement_status" -> agrChkDefault)))
            PServices(s.serviceUrl, s.serviceOrgid, s.serviceArch, s.agreementLess, s.serviceVersions, s.dataVerification, nodeHealth2)
        })
      }
      else
        services
    
    PatternRow(agreementProtocols = write(agreementProtocols2),
               clusterNamespace = clusterNamespace,
               description = description.getOrElse(label),
               label = label,
               lastUpdated = ApiTime.nowUTC,
               orgid = orgid,
               owner = owner,
               pattern = pattern,
               public = public.getOrElse(false),
               services = write(services2),
               secretBinding = write(secretBinding),
               userInput = write(userInput))
  }
}
