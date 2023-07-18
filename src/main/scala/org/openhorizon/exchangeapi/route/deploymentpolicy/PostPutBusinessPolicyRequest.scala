package org.openhorizon.exchangeapi.route.deploymentpolicy

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BService, BusinessPoliciesTQ, BusinessPolicyRow}
import org.openhorizon.exchangeapi.table.service.{OneProperty, ServiceRef2}
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import org.openhorizon.exchangeapi.{ApiTime, ExchConfig}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PostPutBusinessPolicyRequest(label: String,
                                              description: Option[String],
                                              service: BService,
                                              userInput: Option[List[OneUserInputService]],
                                              secretBinding: Option[List[OneSecretBindingService]],
                                              properties: Option[List[OneProperty]],
                                              constraints: Option[List[String]]) {
  require(label != null &&
          service!=null &&
          service.name != null &&
          service.org != null &&
          service.arch != null &&
          service.serviceVersions != null)
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = { BusinessUtils.getAnyProblem(service) }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { BusinessPoliciesTQ.validateServiceIds(service, userInput.getOrElse(List())) }

  // The nodeHealth field is optional, so fill in a default in service if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
  def defaultNodeHealth(service: BService): BService = {
    if (service.nodeHealth.nonEmpty) return service
    val agrChkDefault: Int = ExchConfig.getInt("api.defaults.businessPolicy.check_agreement_status")
    val hbDefault: Int = ExchConfig.getInt("api.defaults.businessPolicy.missing_heartbeat_interval")
    val nodeHealth2: Option[Map[String, Int]] = Option(Map("missing_heartbeat_interval" -> hbDefault, "check_agreement_status" -> agrChkDefault)) // provide defaults for node health
   
    BService(arch = service.arch,
             clusterNamespace = service.clusterNamespace,
             name = service.name,
             nodeHealth = nodeHealth2,
             org = service.org,
             serviceVersions = service.serviceVersions)
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(businessPolicy: String,
                  orgid: String,
                  owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy = businessPolicy,
                      constraints = write(constraints),
                      created = ApiTime.nowUTC,
                      description = description.getOrElse(label),
                      label = label,
                      lastUpdated = ApiTime.nowUTC,
                      orgid = orgid,
                      owner = owner,
                      properties = write(properties),
                      secretBinding = write(secretBinding),
                      service = write(defaultNodeHealth(service)),
                      userInput = write(userInput)).insert
  }

  def getDbUpdate(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    (for {
       deploymentPolicy <-
         BusinessPoliciesTQ.filter(_.businessPolicy === businessPolicy)
                           .map(policy =>
                             (policy.constraints,
                              policy.description,
                              policy.label,
                              policy.lastUpdated,
                              policy.properties,
                              policy.secretBinding,
                              policy.service,
                              policy.userInput))
     } yield(deploymentPolicy))
      .update((write(constraints),
               description.getOrElse(label),
               label,
               ApiTime.nowUTC,
               write(properties),
               write(secretBinding),
               write(defaultNodeHealth(service)),
               write(userInput)))
  }
}
