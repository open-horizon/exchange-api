#!/bin/bash

auth_config="-Djava.security.auth.login.config=exchange_config/jaas.config"
auth_policy="-Djava.security.policy=exchange_config/auth.policy"

for arg in $@
do
  if [[ "${arg}" =~ "-Djava.security.auth.login.config=" ]]; then
    auth_config=""
  fi
  if [[ "${arg}" =~ "-Djava.security.policy=" ]]; then
    auth_policy=""
  fi
done

java -jar $JETTY_HOME/start.jar $auth_config $auth_policy $@
