package org.openhorizon.exchangeapi.auth.cloud

import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import org.pac4j.oidc.config.OidcConfiguration

case class ISV(configuration: OidcConfiguration = new OidcConfiguration().withSecret("").withClientId(""))


//new OidcConfiguration().withClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).withClientId("").withScope("openid").withSecret("")

