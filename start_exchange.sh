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

# Determine if we should configure https support, by whether the key/cert and pw are mounted into the container
if [[ -f "$JETTY_BASE/etc/keystore" && -f "$JETTY_BASE/etc/keypassword" ]]; then
    # Add the https and ssl modules to the jetty config
    java -jar "$JETTY_HOME/start.jar" --create-startd --add-to-start=https,ssl

    # Get the keystore and key pass phrase and set those arguments
    echo "Configuring https/ssl support"
    pw=$(cat $JETTY_BASE/etc/keypassword)
    pw_arg1="-Djetty.sslContext.keyStorePassword=$pw"
    pw_arg2="-Djetty.sslContext.keyManagerPassword=$pw"
    pw_blank_arg1="-Djetty.sslContext.keyStorePassword=****"
    pw_blank_arg2="-Djetty.sslContext.keyManagerPassword=****"
else
    echo "Files keystore and keystore are not in $JETTY_BASE/etc/, so not configuring https/ssl support."
fi


echo java -jar $JETTY_HOME/start.jar $auth_config $auth_policy $pw_blank_arg1 $pw_blank_arg2 $@
java -jar $JETTY_HOME/start.jar $auth_config $auth_policy $pw_arg1 $pw_arg2 $@
