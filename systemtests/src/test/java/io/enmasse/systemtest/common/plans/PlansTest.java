/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.common.plans;

import io.enmasse.systemtest.*;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.TestBase;
import io.enmasse.systemtest.clients.rhea.RheaClientSender;
import io.enmasse.systemtest.resources.*;
import io.enmasse.systemtest.standard.QueueTest;
import io.enmasse.systemtest.standard.TopicTest;
import org.apache.qpid.proton.message.Message;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

@Category(IsolatedAddressSpace.class)
public class PlansTest extends TestBase {

    private static Logger log = CustomLogger.getLogger();

    @Before
    public void setUp() {
        plansProvider.setUp();
    }

    @After
    public void tearDown() {
        plansProvider.tearDown();
    }

    @Override
    protected String getDefaultPlan(AddressType addressType) {
        return null;
    }

    @Test
    public void testCreateAddressSpacePlan() throws Exception {
        //define and create address plans
        List<AddressResource> addressResourcesQueue = Arrays.asList(new AddressResource("broker", 1.0));
        List<AddressResource> addressResourcesTopic = Arrays.asList(
                new AddressResource("broker", 1.0),
                new AddressResource("router", 1.0));
        AddressPlan weakQueuePlan = new AddressPlan("standard-queue-weak", AddressType.QUEUE, addressResourcesQueue);
        AddressPlan weakTopicPlan = new AddressPlan("standard-topic-weak", AddressType.TOPIC, addressResourcesTopic);

        plansProvider.createAddressPlanConfig(weakQueuePlan);
        plansProvider.createAddressPlanConfig(weakTopicPlan);

        //define and create address space plan
        List<AddressSpaceResource> resources = Arrays.asList(
                new AddressSpaceResource("broker", 0.0, 9.0),
                new AddressSpaceResource("router", 1.0, 5.0),
                new AddressSpaceResource("aggregate", 0.0, 10.0));
        List<AddressPlan> addressPlans = Arrays.asList(weakQueuePlan, weakTopicPlan);
        AddressSpacePlan weakSpacePlan = new AddressSpacePlan("weak-plan", "weak",
                "standard-space", AddressSpaceType.STANDARD, resources, addressPlans);
        plansProvider.createAddressSpacePlanConfig(weakSpacePlan);

        //create address space plan with new plan
        AddressSpace weakAddressSpace = new AddressSpace("weak-address-space", AddressSpaceType.STANDARD,
                weakSpacePlan.getName(), AuthService.STANDARD);
        createAddressSpace(weakAddressSpace);

        //deploy destinations
        Destination weakQueueDest = Destination.queue("weak-queue", weakQueuePlan.getName());
        Destination weakTopicDest = Destination.topic("weak-topic", weakTopicPlan.getName());
        setAddresses(weakAddressSpace, weakQueueDest, weakTopicDest);

        //get destinations
        Future<List<Address>> getWeakQueue = getAddressesObjects(weakAddressSpace, Optional.of(weakQueueDest.getAddress()));
        Future<List<Address>> getWeakTopic = getAddressesObjects(weakAddressSpace, Optional.of(weakTopicDest.getAddress()));

        String assertMessage = "Queue plan wasn't set properly";
        assertEquals(assertMessage, getWeakQueue.get(20, TimeUnit.SECONDS).get(0).getPlan(),
                weakQueuePlan.getName());
        assertEquals(assertMessage, getWeakTopic.get(20, TimeUnit.SECONDS).get(0).getPlan(),
                weakTopicPlan.getName());

        //simple send/receive
        String username = "test_newplan_name";
        String password = "test_newplan_password";
        createUser(weakAddressSpace, username, password);
        AmqpClient queueClient = amqpClientFactory.createQueueClient(weakAddressSpace);
        queueClient.getConnectOptions().setUsername(username);
        queueClient.getConnectOptions().setPassword(password);
        QueueTest.runQueueTest(queueClient, weakQueueDest, 42);

        AmqpClient topicClient = amqpClientFactory.createTopicClient(weakAddressSpace);
        topicClient.getConnectOptions().setUsername(username);
        topicClient.getConnectOptions().setPassword(password);
        TopicTest.runTopicTest(topicClient, weakTopicDest, 42);
    }

    @Test
    public void testQuotaLimitsPooled() throws Exception {
        String username = "quota_user";
        String password = "quotaPa55";
        //define and create address plans
        AddressPlan queuePlan = new AddressPlan("queue-pooled-test1", AddressType.QUEUE,
                Collections.singletonList(new AddressResource("broker", 0.6)));

        AddressPlan queuePlan2 = new AddressPlan("queue-pooled-test2", AddressType.QUEUE,
                Collections.singletonList(new AddressResource("broker", 0.1)));

        AddressPlan queuePlan3 = new AddressPlan("queue-pooled-test3", AddressType.QUEUE,
                Collections.singletonList(new AddressResource("broker", 0.05)));

        AddressPlan topicPlan = new AddressPlan("topic-pooled-test1", AddressType.TOPIC,
                Arrays.asList(
                        new AddressResource("broker", 0.4),
                        new AddressResource("router", 0.2)));

        AddressPlan anycastPlan = new AddressPlan("anycast-test1", AddressType.ANYCAST,
                Collections.singletonList(new AddressResource("router", 0.3)));

        plansProvider.createAddressPlanConfig(queuePlan);
        plansProvider.createAddressPlanConfig(queuePlan2);
        plansProvider.createAddressPlanConfig(queuePlan3);
        plansProvider.createAddressPlanConfig(topicPlan);
        plansProvider.createAddressPlanConfig(anycastPlan);

        //define and create address space plan
        List<AddressSpaceResource> resources = Arrays.asList(
                new AddressSpaceResource("broker", 0.0, 2.0),
                new AddressSpaceResource("router", 1.0, 1.0),
                new AddressSpaceResource("aggregate", 0.0, 2.0));
        List<AddressPlan> addressPlans = Arrays.asList(queuePlan, queuePlan2, queuePlan3, topicPlan, anycastPlan);
        AddressSpacePlan addressSpacePlan = new AddressSpacePlan("quota-limits-pooled-plan", "quota-limits-pooled-plan",
                "standard-space", AddressSpaceType.STANDARD, resources, addressPlans);
        plansProvider.createAddressSpacePlanConfig(addressSpacePlan);

        //create address space with new plan
        AddressSpace addressSpace = new AddressSpace("test-quota-limits-pooled", AddressSpaceType.STANDARD,
                addressSpacePlan.getName(), AuthService.STANDARD);
        createAddressSpace(addressSpace);

        getKeycloakClient().createUser(addressSpace.getName(), username, password, 20, TimeUnit.SECONDS);

        //check router limits
        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.anycast("a1", anycastPlan.getName()),
                        Destination.anycast("a2", anycastPlan.getName()),
                        Destination.anycast("a3", anycastPlan.getName())
                ),
                Collections.singletonList(
                        Destination.anycast("a4", anycastPlan.getName())
                ), username, password);

        //check broker limits
        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.queue("q1", queuePlan.getName()),
                        Destination.queue("q2", queuePlan.getName())
                ),
                Collections.singletonList(
                        Destination.queue("q3", queuePlan.getName())
                ), username, password);

        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.queue("q1", queuePlan.getName()), // 0.6
                        Destination.queue("q2", queuePlan.getName()), // 0.6
                        Destination.queue("q3", queuePlan2.getName()), // 0.1
                        Destination.queue("q4", queuePlan2.getName()), // 0.1
                        Destination.queue("q5", queuePlan2.getName()), // 0.1
                        Destination.queue("q6", queuePlan2.getName()), // 0.1
                        Destination.queue("q7", queuePlan3.getName()), // 0.05
                        Destination.queue("q8", queuePlan3.getName()), // 0.05
                        Destination.queue("q9", queuePlan3.getName()), // 0.05
                        Destination.queue("q10", queuePlan3.getName()), // 0.05
                        Destination.queue("q11", queuePlan3.getName()), // 0.05
                        Destination.queue("q12", queuePlan3.getName()) // 0.05
                ), Collections.emptyList(), username, password);

        //check aggregate limits
        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.topic("t1", topicPlan.getName()),
                        Destination.topic("t2", topicPlan.getName())
                ),
                Collections.singletonList(
                        Destination.topic("t3", topicPlan.getName())
                ), username, password);
    }

    @Test
    public void testQuotaLimitsSharded() throws Exception {
        String username = "quota_user";
        String password = "quotaPa55";
        //define and create address plans
        AddressPlan queuePlan = new AddressPlan("queue-sharded-test1", AddressType.QUEUE,
                Collections.singletonList(new AddressResource("broker", 1.0)));

        AddressPlan topicPlan = new AddressPlan("topic-sharded-test2", AddressType.TOPIC,
                Arrays.asList(
                        new AddressResource("broker", 1.0),
                        new AddressResource("router", 0.01)));

        plansProvider.createAddressPlanConfig(queuePlan);
        plansProvider.createAddressPlanConfig(topicPlan);

        //define and create address space plan
        List<AddressSpaceResource> resources = Arrays.asList(
                new AddressSpaceResource("broker", 0.0, 2.0),
                new AddressSpaceResource("router", 1.0, 2.0),
                new AddressSpaceResource("aggregate", 0.0, 3.0));
        List<AddressPlan> addressPlans = Arrays.asList(queuePlan, topicPlan);
        AddressSpacePlan addressSpacePlan = new AddressSpacePlan("quota-limits-sharded-plan", "quota-limits-sharded-plan",
                "standard-space", AddressSpaceType.STANDARD, resources, addressPlans);
        plansProvider.createAddressSpacePlanConfig(addressSpacePlan);

        //create address space with new plan
        AddressSpace addressSpace = new AddressSpace("test-quota-limits-sharded", AddressSpaceType.STANDARD,
                addressSpacePlan.getName(), AuthService.STANDARD);
        createAddressSpace(addressSpace);
        createUser(addressSpace, username, password);

        //check broker limits
        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.queue("q1", queuePlan.getName()),
                        Destination.queue("q2", queuePlan.getName())
                ),
                Collections.singletonList(
                        Destination.queue("q3", queuePlan.getName())
                ), username, password);

        //check aggregate limits
        checkLimits(addressSpace,
                Arrays.asList(
                        Destination.topic("t1", topicPlan.getName()),
                        Destination.topic("t2", topicPlan.getName())
                ),
                Collections.singletonList(
                        Destination.topic("t3", topicPlan.getName())
                ), username, password);
    }

    @Test
    public void testGlobalSizeLimitations() throws Exception {
        KeycloakCredentials user = new KeycloakCredentials("test", "test");
        String messageContent = String.join("", Collections.nCopies(1024, "F"));

        //redefine global max size for queue
        ResourceDefinition limitedResource = new ResourceDefinition(
                "broker",
                "queue-persisted",
                Arrays.asList(
                        new ResourceParameter("GLOBAL_MAX_SIZE", "1Mb")
                ));
        plansProvider.replaceResourceDefinitionConfig(limitedResource);

        //define address plans
        AddressPlan queuePlan = new AddressPlan("limited-queue", AddressType.QUEUE,
                Collections.singletonList(new AddressResource("broker", 0.1))); //should reserve 100Kb

        plansProvider.createAddressPlanConfig(queuePlan);

        //define and create address space plan
        List<AddressSpaceResource> resources = Arrays.asList(
                new AddressSpaceResource("broker", 0.0, 1.0),
                new AddressSpaceResource("router", 1.0, 1.0),
                new AddressSpaceResource("aggregate", 0.0, 2.0));

        AddressSpacePlan addressSpacePlan = new AddressSpacePlan(
                "limited-space",
                "limited-space",
                "standard-space",
                AddressSpaceType.STANDARD,
                resources,
                Collections.singletonList(queuePlan));
        plansProvider.createAddressSpacePlanConfig(addressSpacePlan);

        //create address space with new plan
        AddressSpace addressSpace = new AddressSpace("global-size-limited-space", AddressSpaceType.STANDARD,
                addressSpacePlan.getName(), AuthService.STANDARD);
        createAddressSpace(addressSpace);
        createUser(addressSpace, user.getUsername(), user.getPassword());

        Destination queue = Destination.queue("test-queue", queuePlan.getName());
        Destination queue2 = Destination.queue("test-queue2", queuePlan.getName());
        Destination queue3 = Destination.queue("test-queue3", queuePlan.getName());
        setAddresses(addressSpace, queue, queue2, queue3);

        assertFalse("Client does not fail",
                sendMessage(addressSpace, new RheaClientSender(), user.getUsername(), user.getPassword(),
                        queue.getAddress(), messageContent, 100, false));

        assertFalse("Client does not fail",
                sendMessage(addressSpace, new RheaClientSender(), user.getUsername(), user.getPassword(),
                        queue2.getAddress(), messageContent, 100, false));

        assertTrue("Client fails",
                sendMessage(addressSpace, new RheaClientSender(), user.getUsername(), user.getPassword(),
                        queue3.getAddress(), messageContent, 50, false));
    }

    @Test
    public void testMessagePersistenceAfterManualScaleDown() throws Exception {
        //define and create address plans
        List<AddressResource> addressResourcesQueue = Arrays.asList(new AddressResource("broker", 0.4));
        AddressPlan queuePlan = new AddressPlan("pooled-standard-queue-beta", AddressType.QUEUE, addressResourcesQueue);
        plansProvider.createAddressPlanConfig(queuePlan);

        //define and create address space plan
        List<AddressSpaceResource> resources = Arrays.asList(
                new AddressSpaceResource("broker", 0.0, 9.0),
                new AddressSpaceResource("router", 1.0, 5.0),
                new AddressSpaceResource("aggregate", 0.0, 10.0));
        List<AddressPlan> addressPlans = Arrays.asList(queuePlan);
        AddressSpacePlan scaleSpacePlan = new AddressSpacePlan("scale-plan", "scaleplan",
                "standard-space", AddressSpaceType.STANDARD, resources, addressPlans);
        plansProvider.createAddressSpacePlanConfig(scaleSpacePlan);

        //create address space plan with new plan
        AddressSpace scaleAddressSpace = new AddressSpace("scale-space-standard", AddressSpaceType.STANDARD,
                scaleSpacePlan.getName(), AuthService.STANDARD);
        createAddressSpace(scaleAddressSpace);

        //deploy destinations
        Destination queue1 = Destination.queue("queue1", queuePlan.getName());
        Destination queue2 = Destination.queue("queue2", queuePlan.getName());
        Destination queue3 = Destination.queue("queue3", queuePlan.getName());
        Destination queue4 = Destination.queue("queue4", queuePlan.getName());

        setAddresses(scaleAddressSpace, queue1, queue2, queue3, queue4);

        //send 1000 messages to each queue
        String username = "test_scale_user_name";
        String password = "test_scale_user_pswd";
        createUser(scaleAddressSpace, username, password);

        AmqpClient queueClient = amqpClientFactory.createQueueClient(scaleAddressSpace);
        queueClient.getConnectOptions().setUsername(username);
        queueClient.getConnectOptions().setPassword(password);

        List<String> msgs = TestUtils.generateMessages(1000);
        Future<Integer> sendResult1 = queueClient.sendMessages(queue1.getAddress(), msgs);
        Future<Integer> sendResult2 = queueClient.sendMessages(queue2.getAddress(), msgs);
        Future<Integer> sendResult3 = queueClient.sendMessages(queue3.getAddress(), msgs);
        Future<Integer> sendResult4 = queueClient.sendMessages(queue4.getAddress(), msgs);

        assertThat("Incorrect count of messages sent", sendResult1.get(1, TimeUnit.MINUTES), is(msgs.size()));
        assertThat("Incorrect count of messages sent", sendResult2.get(1, TimeUnit.MINUTES), is(msgs.size()));
        assertThat("Incorrect count of messages sent", sendResult3.get(1, TimeUnit.MINUTES), is(msgs.size()));
        assertThat("Incorrect count of messages sent", sendResult4.get(1, TimeUnit.MINUTES), is(msgs.size()));

        //scale broker down and wait until broker statefulset will scaled up to 2 automatically
        scale(scaleAddressSpace, queue4, 1, 10);
        TestUtils.waitForNBrokerReplicas(kubernetes, scaleAddressSpace.getNamespace(), 2, queue4, new TimeoutBudget(2, TimeUnit.MINUTES));

        //check that messages weren't lost
        Future<List<Message>> recvResult1 = queueClient.recvMessages(queue1.getAddress(), msgs.size());
        Future<List<Message>> recvResult2 = queueClient.recvMessages(queue2.getAddress(), msgs.size());
        Future<List<Message>> recvResult3 = queueClient.recvMessages(queue3.getAddress(), msgs.size());
        Future<List<Message>> recvResult4 = queueClient.recvMessages(queue4.getAddress(), msgs.size());

        Future<List<String>> addresses = getAddresses(scaleAddressSpace, Optional.empty());
        List<String> addressNames = addresses.get(15, TimeUnit.SECONDS);
        assertThat(String.format("Unexpected count of destinations, got following: %s", addressNames),
                addressNames.size(), is(4));

        assertThat("Incorrect count of messages received", recvResult1.get(1, TimeUnit.MINUTES).size(), is(msgs.size()));
        assertThat("Incorrect count of messages received", recvResult2.get(1, TimeUnit.MINUTES).size(), is(msgs.size()));
        assertThat("Incorrect count of messages received", recvResult3.get(1, TimeUnit.MINUTES).size(), is(msgs.size()));
        assertThat("Incorrect count of messages received", recvResult4.get(1, TimeUnit.MINUTES).size(), is(msgs.size()));
    }
    //------------------------------------------------------------------------------------------------
    // Help methods
    //------------------------------------------------------------------------------------------------

    private void checkLimits(AddressSpace addressSpace, List<Destination> allowedDest, List<Destination> notAllowedDest, String username, String password)
            throws Exception {

        log.info("Try to create {} addresses, and make sure that {} addresses will be not created",
                Arrays.toString(allowedDest.stream().map(Destination::getName).toArray(String[]::new)),
                Arrays.toString(notAllowedDest.stream().map(Destination::getName).toArray(String[]::new)));

        setAddresses(addressSpace, new TimeoutBudget(10, TimeUnit.MINUTES), allowedDest.toArray(new Destination[0]));
        List<Future<List<Address>>> getAddresses = new ArrayList<>();
        for (Destination dest : allowedDest) {
            getAddresses.add(getAddressesObjects(addressSpace, Optional.of(dest.getAddress())));
        }

        for (Future<List<Address>> getAddress : getAddresses) {
            Address address = getAddress.get(20, TimeUnit.SECONDS).get(0);
            log.info("Address {} with plan {} is in phase {}", address.getName(), address.getPlan(), address.getPhase());
            String assertMessage = String.format("Address from allowed %s is not ready", address.getName());
            assertEquals(assertMessage, "Active", address.getPhase());
        }

        assertCanConnect(addressSpace, username, password, allowedDest);

        getAddresses.clear();
        if (notAllowedDest.size() > 0) {
            try {
                appendAddresses(addressSpace, new TimeoutBudget(30, TimeUnit.SECONDS), notAllowedDest.toArray(new Destination[0]));
            } catch (IllegalStateException ex) {
                if (!ex.getMessage().contains("addresses are not ready")) {
                    throw ex;
                }
            }

            for (Destination dest : notAllowedDest) {
                getAddresses.add(getAddressesObjects(addressSpace, Optional.of(dest.getAddress())));
            }

            for (Future<List<Address>> getAddress : getAddresses) {
                Address address = getAddress.get(20, TimeUnit.SECONDS).get(0);
                log.info("Address {} with plan {} is in phase {}", address.getName(), address.getPlan(), address.getPhase());
                String assertMessage = String.format("Address from notAllowed %s is ready", address.getName());
                assertEquals(assertMessage, "Pending", address.getPhase());
                assertTrue("No status message is present", address.getStatusMessages().contains("Quota exceeded"));
            }
        }

        setAddresses(addressSpace);
    }
}
