package org.openhorizon.exchangeapi.route.deploymentpattern

import org.openhorizon.exchangeapi.table.deploymentpattern.PServices
import org.openhorizon.exchangeapi.utility.{ExchMsg, Version}

object PatternUtils {
  def validatePatternServices(services: List[PServices]): Option[String] = {
    // Check that it is signed and check the version syntax
    for (s <- services) {
      if (s.serviceVersions.isEmpty) return Option(ExchMsg.translate("no.version.specified.for.service", s.serviceOrgid, s.serviceUrl, s.serviceArch))
      for (sv <- s.serviceVersions) {
        if (!Version(sv.version).isValid) return Option(ExchMsg.translate("version.not.valid.format", sv.version))
        if (sv.deployment_overrides.getOrElse("") != "" && sv.deployment_overrides_signature.getOrElse("") == "") {
          return Option(ExchMsg.translate("pattern.definition.not.signed"))
        }
      }
    }
    None
  }
}
