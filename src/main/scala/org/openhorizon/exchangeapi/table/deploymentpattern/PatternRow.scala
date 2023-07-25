package org.openhorizon.exchangeapi.table.deploymentpattern

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatternRow(pattern: String,
                            orgid: String,
                            owner: String,
                            label: String,
                            description: String,
                            public: Boolean,
                            services: String,
                            userInput: String,
                            secretBinding: String,
                            agreementProtocols: String,
                            lastUpdated: String,
                            clusterNamespace: Option[String] = None) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toPattern: Pattern = {
    val agproto: List[Map[String, String]] = if (agreementProtocols != "") read[List[Map[String,String]]](agreementProtocols) else List[Map[String,String]]()
    val bind: List[OneSecretBindingService] = if (secretBinding != "") read[List[OneSecretBindingService]](secretBinding) else List[OneSecretBindingService]()
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val svc: List[PServices] = if (services == "") List[PServices]() else read[List[PServices]](services)
    
    new Pattern(agreementProtocols = agproto,
                clusterNamespace = clusterNamespace.getOrElse(""),
                description = description,
                label = label,
                lastUpdated = lastUpdated,
                owner = owner,
                public = public,
                secretBinding = bind,
                services = svc,
                userInput = input)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- PatternsTQ if m.pattern === pattern } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = PatternsTQ += this
}
