package org.openhorizon.exchangeapi.route.user.apikey

final case class PostApiKeyRequest(description: Option[String] = None,
                                   label: Option[String] = None)

