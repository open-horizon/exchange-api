package org.openhorizon.exchangeapi.table.deploymentpolicy

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import org.openhorizon.exchangeapi.table.service.OneProperty
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class BusinessPolicyRow(businessPolicy: String,
                                   orgid: String,
                                   owner: String,
                                   label: String,
                                   description: String,
                                   service: String,
                                   userInput: String,
                                   secretBinding: String,
                                   properties: String,
                                   constraints: String,
                                   lastUpdated: String,
                                   created: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toBusinessPolicy: BusinessPolicy = {
    val bind: List[OneSecretBindingService] = if (secretBinding != "") read[List[OneSecretBindingService]](secretBinding) else List[OneSecretBindingService]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val serv: BService = read[BService](service)
    
    
    new BusinessPolicy(constraints = con,
                       created = created,
                       description = description,
                       label = label,
                       lastUpdated = lastUpdated,
                       owner = owner,
                       properties = prop,
                       secretBinding = bind,
                       service = serv.copy(clusterNamespace = serv.clusterNamespace.orElse(Option(""))),  // Agent cannot read null values. Substitute in an empty string. Writing None, reading "".
                       userInput = input)
  }

  // update returns a DB action to update this row
  //todo: we should not update the 'created' field, but we also don't want to list out all of the other fields, because it is error prone
  def update: DBIO[_] =
    (for {
       m <- BusinessPoliciesTQ if m.businessPolicy === businessPolicy
     } yield (m.businessPolicy,
              m.orgid,
              m.owner,
              m.label,
              m.description,
              m.service,
              m.userInput,
              m.secretBinding,
              m.properties,
              m.constraints,
              m.lastUpdated))
      .update((businessPolicy,
               orgid,
               owner,
               label,
               description,
               service,
               userInput,
               secretBinding,
               properties,
               constraints,
               lastUpdated))

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = BusinessPoliciesTQ += this
}
