FROM quay.io/keycloak/keycloak:26.4.7@sha256:9409c59bdfb65dbffa20b11e6f18b8abb9281d480c7ca402f51ed3d5977e6007
COPY --chown=keycloak:keycloak deploy/keycloak/themes/repricer /opt/keycloak/themes/repricer
COPY --chown=keycloak:keycloak deploy/keycloak/realm.json /opt/keycloak/data/import/repricer-realm.json
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --http-relative-path=/auth
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
