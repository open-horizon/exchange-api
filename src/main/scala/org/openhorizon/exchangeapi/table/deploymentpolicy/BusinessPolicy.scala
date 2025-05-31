package org.openhorizon.exchangeapi.table.deploymentpolicy

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import org.openhorizon.exchangeapi.table.service.OneProperty

final case class BusinessPolicy(
                           constraints: List[String],
                           created: String,
                           description: String,
                           label: String,
                           lastUpdated: String,
                           owner: String,
                           properties: List[OneProperty],
                           secretBinding: List[OneSecretBindingService],
                           service: BService,
                           userInput: List[OneUserInputService]) {
  
  def this(tuple: (String, String, String, String, String, String, String, String, String, String))(implicit defaultFormats: Formats) =
    this(constraints = if (tuple._1 != "") read[List[String]](tuple._1) else List[String](),
        created = tuple._2,
        description = tuple._3,
        label = tuple._4,
        lastUpdated = tuple._5,
        owner = tuple._6,
        properties = if (tuple._7 != "") read[List[OneProperty]](tuple._7) else List[OneProperty](),
        secretBinding = if (tuple._8 != "") read[List[OneSecretBindingService]](tuple._8) else List[OneSecretBindingService](),
        service = read[BService](tuple._9),
        userInput = if (tuple._10 != "") read[List[OneUserInputService]](tuple._10) else List[OneUserInputService]())
}
