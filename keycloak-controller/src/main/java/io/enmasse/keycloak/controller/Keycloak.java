/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.keycloak.controller;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Keycloak implements KeycloakApi {

    private final KeycloakParams params;

    public Keycloak(KeycloakParams params) {
        this.params = params;
    }

    @Override
    public Set<String> getRealmNames() {
        try (CloseableKeycloak wrapper = new CloseableKeycloak(params)) {
            return wrapper.get().realms().findAll().stream()
                    .map(RealmRepresentation::getRealm)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public void createRealm(String realmName, String realmAdminUser, String consoleRedirectURI) {
        final RealmRepresentation newRealm = new RealmRepresentation();
        newRealm.setRealm(realmName);
        newRealm.setEnabled(true);
        newRealm.setPasswordPolicy("hashAlgorithm(scramsha1)");

        final UserRepresentation newUser = new UserRepresentation();
        newUser.setUsername(realmAdminUser);

        newUser.setEnabled(true);
        newUser.setClientRoles(Collections.singletonMap("realm-management", Collections.singletonList("manage-users")));

        newRealm.setUsers(Collections.singletonList(newUser));

        if (consoleRedirectURI != null) {
            final ClientRepresentation console = new ClientRepresentation();
            console.setClientId("enmasse-console");
            console.setPublicClient(true);
            console.setRedirectUris(Collections.singletonList(consoleRedirectURI));
            newRealm.setClients(Collections.singletonList(console));
        }

        try (CloseableKeycloak wrapper = new CloseableKeycloak(params)) {
            wrapper.get().realms().create(newRealm);
        }
    }

    @Override
    public void deleteRealm(String realmName) {
        try (CloseableKeycloak wrapper = new CloseableKeycloak(params)) {
            wrapper.get().realm(realmName).remove();
        }
    }

    public static class CloseableKeycloak implements AutoCloseable {

        private final org.keycloak.admin.client.Keycloak keycloak;

        CloseableKeycloak(KeycloakParams params) {
            this.keycloak = KeycloakBuilder.builder()
                .serverUrl(params.getKeycloakUri())
                .realm("master")
                .username(params.getAdminUser())
                .password(params.getAdminPassword())
                .clientId("admin-cli")
                .resteasyClient(new ResteasyClientBuilder()
                        .establishConnectionTimeout(30, TimeUnit.SECONDS)
                        .trustStore(params.getKeyStore())
                        .hostnameVerification(ResteasyClientBuilder.HostnameVerificationPolicy.ANY)
                        .build())
                .build();
        }

        org.keycloak.admin.client.Keycloak get() {
            return keycloak;
        }

        @Override
        public void close() {
            keycloak.close();
        }
    }
}
