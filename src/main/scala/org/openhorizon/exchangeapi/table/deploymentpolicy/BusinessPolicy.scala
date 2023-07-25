package org.openhorizon.exchangeapi.table.deploymentpolicy

import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import org.openhorizon.exchangeapi.table.service.OneProperty

class BusinessPolicy(var owner: String,
                     var label: String,
                     var description: String,
                     var service: BService,
                     var userInput: List[OneUserInputService],
                     var secretBinding: List[OneSecretBindingService],
                     var properties: List[OneProperty],
                     var constraints: List[String],
                     var lastUpdated: String,
                     var created: String) {
  def copy = new BusinessPolicy(constraints = constraints,
                                created = created,
                                description = description,
                                label = label,
                                lastUpdated = lastUpdated,
                                owner = owner,
                                properties = properties,
                                secretBinding = secretBinding,
                                service = service,
                                userInput = userInput)
}
