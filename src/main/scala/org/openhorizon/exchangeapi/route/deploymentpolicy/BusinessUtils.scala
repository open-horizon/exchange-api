package org.openhorizon.exchangeapi.route.deploymentpolicy

import org.openhorizon.exchangeapi.table.deploymentpolicy.BService
import org.openhorizon.exchangeapi.utility.{ExchMsg, Version}

object BusinessUtils {
  def getAnyProblem(service: BService): Option[String] = {
    // Ensure the references to the service are not null
    if (service.name==null || service.org==null || service.arch==null) return Option(ExchMsg.translate("no.service.ref.specified.for.service"))
    // Check they specified at least 1 service version
    if (service.serviceVersions==null || service.serviceVersions.isEmpty) return Option(ExchMsg.translate("no.version.specified.for.service2"))
    // Check the version syntax
    for (sv <- service.serviceVersions) {
      if (!Version(sv.version).isValid) return Option(ExchMsg.translate("version.not.valid.format", sv.version))
    }
    None
  }
}
