#!/bin/bash

# Used in Dockerfile-exec to start the exchange and allow the auth args to be overwritten

auth_config="-Djava.security.auth.login.config=exchange_config/jaas.config"
auth_policy="-Djava.security.policy=exchange_config/auth.policy"

# Allow overriding of the location of the auth files
for arg in $@
do
  if [[ "${arg}" =~ "-Djava.security.auth.login.config=" ]]; then
    auth_config=""
  fi
  if [[ "${arg}" =~ "-Djava.security.policy=" ]]; then
    auth_policy=""
  fi
done

# Get the keystore and key pw
if [[ -n "$JETTY_BASE/etc/keypassword" ]]; then
    pw=$(cat $JETTY_BASE/etc/keypassword)
    pw_arg1="-Djetty.sslContext.keyStorePassword=$pw"
    pw_arg2="-Djetty.sslContext.keyManagerPassword=$pw"
else
    echo "Error: $JETTY_BASE/etc/keypassword does not exist. Need that to access the certificate keystore."
    # and we won't set the pw args, which result in an error accessing the keystore when starting
fi


echo java -jar $JETTY_HOME/start.jar $auth_config $auth_policy $pw_arg1 $pw_arg2 $@
java -jar $JETTY_HOME/start.jar $auth_config $auth_policy $pw_arg1 $pw_arg2 $@
