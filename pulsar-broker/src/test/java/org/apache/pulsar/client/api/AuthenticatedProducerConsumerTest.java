/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.InternalServerErrorException;

import org.apache.pulsar.broker.authentication.AuthenticationProviderBasic;
import org.apache.pulsar.broker.authentication.AuthenticationProviderTls;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.impl.auth.AuthenticationBasic;
import org.apache.pulsar.client.impl.auth.AuthenticationTls;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.Sets;

public class AuthenticatedProducerConsumerTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(AuthenticatedProducerConsumerTest.class);

    private final String TLS_TRUST_CERT_FILE_PATH = "./src/test/resources/authentication/tls/cacert.pem";
    private final String TLS_SERVER_CERT_FILE_PATH = "./src/test/resources/authentication/tls/broker-cert.pem";
    private final String TLS_SERVER_KEY_FILE_PATH = "./src/test/resources/authentication/tls/broker-key.pem";
    private final String TLS_CLIENT_CERT_FILE_PATH = "./src/test/resources/authentication/tls/client-cert.pem";
    private final String TLS_CLIENT_KEY_FILE_PATH = "./src/test/resources/authentication/tls/client-key.pem";

    private final String BASIC_CONF_FILE_PATH = "./src/test/resources/authentication/basic/.htpasswd";

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        if (methodName.equals("testAnonymousSyncProducerAndConsumer")) {
            conf.setAnonymousUserRole("anonymousUser");
        }

        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);

        conf.setBrokerServicePortTls(Optional.of(BROKER_PORT_TLS));
        conf.setWebServicePortTls(Optional.of(BROKER_WEBSERVICE_PORT_TLS));
        conf.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);
        conf.setTlsCertificateFilePath(TLS_SERVER_CERT_FILE_PATH);
        conf.setTlsKeyFilePath(TLS_SERVER_KEY_FILE_PATH);
        conf.setTlsAllowInsecureConnection(true);

        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add("localhost");
        superUserRoles.add("superUser");
        superUserRoles.add("superUser2");
        conf.setSuperUserRoles(superUserRoles);

        conf.setBrokerClientAuthenticationPlugin(AuthenticationTls.class.getName());
        conf.setBrokerClientAuthenticationParameters(
                "tlsCertFile:" + TLS_CLIENT_CERT_FILE_PATH + "," + "tlsKeyFile:" + TLS_SERVER_KEY_FILE_PATH);

        Set<String> providers = new HashSet<>();
        providers.add(AuthenticationProviderTls.class.getName());
        providers.add(AuthenticationProviderBasic.class.getName());
        System.setProperty("pulsar.auth.basic.conf", BASIC_CONF_FILE_PATH);
        conf.setAuthenticationProviders(providers);

        conf.setClusterName("test");

        super.init();
    }

    protected final void internalSetup(Authentication auth) throws Exception {
        admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrlTls.toString())
                .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH).allowTlsInsecureConnection(true).authentication(auth)
                .build());
        String lookupUrl;
        // For http basic authentication test
        if (methodName.equals("testBasicCryptSyncProducerAndConsumer")) {
            lookupUrl = new URI("https://localhost:" + BROKER_WEBSERVICE_PORT_TLS).toString();
        } else {
            lookupUrl = new URI("pulsar+ssl://localhost:" + BROKER_PORT_TLS).toString();
        }
        pulsarClient = PulsarClient.builder().serviceUrl(lookupUrl).statsInterval(0, TimeUnit.SECONDS)
                .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH).allowTlsInsecureConnection(true).authentication(auth)
                .enableTls(true).build();
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @DataProvider(name = "batch")
    public Object[][] codecProvider() {
        return new Object[][] { { 0 }, { 1000 } };
    }

    public void testSyncProducerAndConsumer(int batchMessageDelayMs) throws Exception {
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://my-property/my-ns/my-topic")
                .subscriptionName("my-subscriber-name").subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer().topic("persistent://my-property/my-ns/my-topic");

        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true);
            producerBuilder.batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS);
            producerBuilder.batchingMaxMessages(5);
        }

        Producer<byte[]> producer = producerBuilder.create();
        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = Sets.newHashSet();
        for (int i = 0; i < 10; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.debug("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }
        // Acknowledge the consumption of all messages at once
        consumer.acknowledgeCumulative(msg);
        consumer.close();
    }

    @Test(dataProvider = "batch")
    public void testTlsSyncProducerAndConsumer(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);

        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);
        internalSetup(authTls);

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace("my-property/my-ns", Sets.newHashSet("test"));

        testSyncProducerAndConsumer(batchMessageDelayMs);

        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch")
    public void testBasicCryptSyncProducerAndConsumer(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);
        AuthenticationBasic authPassword = new AuthenticationBasic();
        authPassword.configure("{\"userId\":\"superUser\",\"password\":\"supepass\"}");
        internalSetup(authPassword);

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet(), Sets.newHashSet("test")));
        admin.namespaces().createNamespace("my-property/my-ns", Sets.newHashSet("test"));

        testSyncProducerAndConsumer(batchMessageDelayMs);

        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch")
    public void testBasicArp1SyncProducerAndConsumer(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);
        AuthenticationBasic authPassword = new AuthenticationBasic();
        authPassword.configure("{\"userId\":\"superUser2\",\"password\":\"superpassword\"}");
        internalSetup(authPassword);

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet(), Sets.newHashSet("test")));
        admin.namespaces().createNamespace("my-property/my-ns", Sets.newHashSet("test"));

        testSyncProducerAndConsumer(batchMessageDelayMs);

        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch")
    public void testAnonymousSyncProducerAndConsumer(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);

        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);
        internalSetup(authTls);

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString(), brokerUrlTls.toString(),
                "pulsar://localhost:" + BROKER_PORT, "pulsar+ssl://localhost:" + BROKER_PORT_TLS));
        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet("anonymousUser"), Sets.newHashSet("test")));

        // make a PulsarAdmin instance as "anonymousUser" for http request
        admin.close();
        admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString()).build());
        admin.namespaces().createNamespace("my-property/my-ns", Sets.newHashSet("test"));
        admin.topics().grantPermission("persistent://my-property/my-ns/my-topic", "anonymousUser",
                EnumSet.allOf(AuthAction.class));

        // setup the client
        pulsarClient.close();
        pulsarClient = PulsarClient.builder().serviceUrl("pulsar://localhost:" + BROKER_PORT)
                .operationTimeout(1, TimeUnit.SECONDS).build();

        // unauthorized topic test
        Exception pulsarClientException = null;
        try {
            pulsarClient.newConsumer().topic("persistent://my-property/my-ns/other-topic")
                    .subscriptionName("my-subscriber-name").subscribe();
        } catch (Exception e) {
            pulsarClientException = e;
        }
        Assert.assertTrue(pulsarClientException instanceof PulsarClientException);

        testSyncProducerAndConsumer(batchMessageDelayMs);

        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * Verifies: on 500 server error, broker invalidates session and client receives 500 correctly.
     *
     * @throws Exception
     */
    @Test
    public void testAuthenticationFilterNegative() throws Exception {
        log.info("-- Starting {} test --", methodName);

        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);
        internalSetup(authTls);

        final String cluster = "test";
        final ClusterData clusterData = new ClusterData(brokerUrl.toString(), brokerUrlTls.toString(),
                "pulsar://localhost:" + BROKER_PORT, "pulsar+ssl://localhost:" + BROKER_PORT_TLS);
        // this will cause NPE and it should throw 500
        doReturn(null).when(pulsar).getGlobalZkCache();
        try {
            admin.clusters().createCluster(cluster, clusterData);
        } catch (PulsarAdminException e) {
            Assert.assertTrue(e.getCause() instanceof InternalServerErrorException);
        }

        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * verifies that topicLookup/PartitionMetadataLookup gives InternalServerError(500) instead 401(auth_failed) on
     * unknown-exception failure
     *
     * @throws Exception
     */
    @Test
    public void testInternalServerExceptionOnLookup() throws Exception {
        log.info("-- Starting {} test --", methodName);

        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);
        internalSetup(authTls);

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString(), brokerUrlTls.toString(),
                "pulsar://localhost:" + BROKER_PORT, "pulsar+ssl://localhost:" + BROKER_PORT_TLS));
        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        String namespace = "my-property/my-ns";
        admin.namespaces().createNamespace(namespace, Sets.newHashSet("test"));

        String topic = "persistent://" + namespace + "1/topic1";
        // this will cause NPE and it should throw 500
        mockZookKeeper.shutdown();
        pulsar.getConfiguration().setSuperUserRoles(Sets.newHashSet());
        try {
            admin.topics().getPartitionedTopicMetadata(topic);
        } catch (PulsarAdminException e) {
            Assert.assertTrue(e.getCause() instanceof InternalServerErrorException);
        }
        try {
            admin.lookups().lookupTopic(topic);
        } catch (PulsarAdminException e) {
            Assert.assertTrue(e.getCause() instanceof InternalServerErrorException);
        }

    }

}
