package org.openhorizon.exchangeapi.route.user.apikey

final case class PostApiKeyResponse(description: String = "",
                                    id: String,
                                    label: String = "",
                                    lastUpdated: String,
                                    owner: String,
                                    value: String)