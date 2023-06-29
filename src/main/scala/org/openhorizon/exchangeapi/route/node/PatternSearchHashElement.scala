package org.openhorizon.exchangeapi.route.node

// Tried this to have names on the tuple returned from the db, but didn't work...
final case class PatternSearchHashElement(nodeType: String, publicKey: String, noAgreementYet: Boolean)
