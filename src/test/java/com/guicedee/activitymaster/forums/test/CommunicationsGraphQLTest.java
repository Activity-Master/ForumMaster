package com.guicedee.activitymaster.forums.test;

import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.client.IGuiceContext;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.guicedee.client.implementations.ObjectBinderKeys.DefaultObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real SPI schema composition, HTTP call scopes and canonical PostgreSQL storage. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunicationsGraphQLTest {
    private CommunicationsTestFixture fixture;
    private HttpClient client;
    private ObjectMapper mapper;
    private URI endpoint;
    private String owner, reader, outsider;
    private static final String FORUM = "{ id realm ownerId title subscribers }";
    private static final String CONVERSATION = "{ id realm ownerId participants }";
    private static final String MESSAGE = "{ id conversationId senderId text createdAt }";
    private static final String POST = "{ id forumId posterId text createdAt }";
    private static final String NOTICE = "{ id category severity subject body data publisherId createdAt state }";

    @BeforeAll void setup() throws Exception {
        fixture = CommunicationsTestFixture.get();
        owner = CommunicationsTestHost.issue(identity(fixture.actorId, fixture.enterpriseId, fixture.token));
        reader = CommunicationsTestHost.issue(identity(fixture.recipientId, fixture.enterpriseId, fixture.tokenFor(fixture.recipientId)));
        outsider = CommunicationsTestHost.issue(identity(fixture.outsiderId, fixture.enterpriseId, fixture.tokenFor(fixture.outsiderId)));
        mapper = IGuiceContext.get(DefaultObjectMapper);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (CommunicationsTestHost.server == null || CommunicationsTestHost.server.actualPort() <= 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("GraphQL HTTP listener did not start");
            Thread.sleep(50);
        }
        endpoint = URI.create("http://127.0.0.1:" + CommunicationsTestHost.server.actualPort() + "/graphql");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll void close() { if (client != null) client.close(); }
    private NotificationIdentity identity(UUID party, UUID enterprise, UUID token) {
        return new NotificationIdentity(party, enterprise, new ActivityScope.Context(ActivityScope.Realm.WORK, enterprise), token);
    }
    private JsonNode execute(String query, Map<String, Object> variables, String credential) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (credential != null) request.header("X-Communications-Test-Credential", credential);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private JsonNode field(boolean mutation, String field, String args, String selection, String type,
                           Map<String, Object> variables, String credential) throws Exception {
        String declaration = type == null ? "" : "($input: " + type + "!)";
        return execute((mutation ? "mutation" : "query") + declaration + " { " + field + "(enterprise: \""
                + CommunicationsTestFixture.ENTERPRISE + "\"" + (args.isEmpty() ? "" : ", " + args) + ") " + selection + " }", variables, credential);
    }
    private JsonNode read(String field, String args, String selection, String credential) throws Exception {
        return field(false, field, args, selection, null, Map.of(), credential);
    }
    private JsonNode write(String field, String args, String selection, String credential) throws Exception {
        return field(true, field, args, selection, null, Map.of(), credential);
    }
    private JsonNode result(JsonNode response, String field) {
        assertFalse(response.has("errors"), response.toString());
        return response.get("data").get(field);
    }
    private void error(JsonNode response, String code) {
        assertTrue(response.has("errors"), response.toString());
        assertEquals(code, response.get("errors").get(0).path("extensions").path("code").asString(), response.toString());
    }
    private static String id(String name, Object value) { return name + ": \"" + value + "\""; }
    private String forum() throws Exception {
        return result(field(true, "forumCreate", "input: $input", FORUM, "ForumCreateInput",
                Map.of("input", Map.of("realm", "WORK", "ownerId", fixture.enterpriseId.toString(),
                        "title", "GraphQL forum", "subscribers", List.of(fixture.recipientId.toString()))), owner), "forumCreate").get("id").asString();
    }
    private String conversation() throws Exception {
        return result(field(true, "conversationCreate", "input: $input", CONVERSATION, "ConversationCreateInput",
                Map.of("input", Map.of("realm", "WORK", "ownerId", fixture.enterpriseId.toString(),
                        "participants", List.of(fixture.recipientId.toString()))), owner), "conversationCreate").get("id").asString();
    }
    private String publish(String category) throws Exception {
        return result(field(true, "notificationPublish", "input: $input", "{ id recipients }", "NotificationPublishInput",
                Map.of("input", Map.of("category", category, "subject", "GraphQL notice", "body", "Full body",
                        "recipients", List.of(fixture.recipientId.toString()))), owner), "notificationPublish").get("id").asString();
    }

    @Test void discoversAllProvidersAndComposesTheirTypedRoots() throws Exception {
        var response = execute("{ __type(name: \"Query\") { fields { name } } }", Map.of(), null);
        assertFalse(response.has("errors"), response.toString());
        var mutations = execute("{ __type(name: \"Mutation\") { fields { name } } }", Map.of(), null);
        assertFalse(mutations.has("errors"), mutations.toString());
        String schema = response.get("data").toString() + mutations.get("data").toString();
        for (String name : List.of("forum", "forums", "forumPosts", "conversation", "conversations", "conversationMessages",
                "notification", "notifications", "notificationCounts", "notificationDeliveries", "forumCreate", "forumUpdateSubscribers",
                "conversationCreate", "conversationSend", "notificationPublish", "notificationReadAll")) assertTrue(schema.contains("\"" + name + "\""), name);
    }

    @Test void graphQLPreservesHostPluginContextAndRejectsUndelegatedCredentials() throws Exception {
        // The ordinary test host uses a system credential. Attaching a plugin must never
        // silently downgrade this request to the legacy, non-plugin identity path.
        result(read("forums", "", "{ items { id } }", owner), "forums");
        NotificationIdentity plain = identity(fixture.actorId, fixture.enterpriseId, fixture.token);
        String delegated = CommunicationsTestHost.issue(new NotificationIdentity(plain.partyId(), plain.enterpriseId(),
                plain.context(), plain.identityToken(), new com.guicedee.activitymaster.fsdm.plugins.PluginModels.Invocation(
                        UUID.randomUUID(), fixture.actorId)));
        error(read("forums", "", "{ items { id } }", delegated), "FORBIDDEN");
        error(read("notifications", "", "{ items { id } }", delegated), "FORBIDDEN");
    }

    @Test void forumPostsPublishNoticesAndRetainPagingAndRecords() throws Exception {
        String forum = forum();
        var post = result(write("forumPost", id("forumId", forum) + ", input: {text: \"Hello 🌍\"}", POST, owner), "forumPost");
        assertEquals(fixture.actorId.toString(), post.get("posterId").asString());
        assertFalse(post.get("createdAt").asString().isBlank());
        var page = result(read("forumPosts", id("forumId", forum), "{ offset limit hasMore items " + POST + " }", reader), "forumPosts");
        assertEquals(0, page.get("offset").asInt()); assertEquals(50, page.get("limit").asInt());
        assertEquals("Hello 🌍", page.get("items").get(0).get("text").asString());
        result(write("forumPost", id("forumId", forum) + ", input: {text: \"Second\"}", POST, owner), "forumPost");
        assertTrue(result(read("forumPosts", id("forumId", forum) + ", limit: 1", "{ hasMore items { id } }", reader), "forumPosts").get("hasMore").asBoolean());
        assertTrue(result(read("forums", "", "{ limit items { id } }", reader), "forums").get("items").size() > 0);
        var notices = result(read("notifications", "category: \"forum.post\"", "{ items " + NOTICE + " }", reader), "notifications").get("items");
        assertTrue(notices.size() >= 2);
        assertTrue(notices.get(0).get("body").isNull());
        String matchingNotice = null;
        for (JsonNode notice : notices) {
            var full = result(read("notification", id("notificationId", notice.get("id").asString()), NOTICE, reader), "notification");
            if (full.get("data").asString().contains(post.get("id").asString())) {
                matchingNotice = notice.get("id").asString();
                assertEquals("Hello 🌍", full.get("body").asString());
            }
        }
        assertNotNull(matchingNotice);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode deliveries;
        do {
            deliveries = result(read("notificationDeliveries", id("notificationId", matchingNotice),
                    "{ items { id notification recipientId channel result detail attemptedAt } }", owner), "notificationDeliveries").get("items");
            if (!deliveries.isEmpty()) break;
            assertTrue(System.nanoTime() < deadline, "Post-commit delivery did not complete");
            Thread.sleep(50);
        } while (true);
        assertEquals("EVENT_BUS", deliveries.get(0).get("channel").asString());
        assertEquals("SENT", deliveries.get(0).get("result").asString());
        error(read("forum", id("forumId", forum), FORUM, outsider), "NOT_FOUND");
    }

    @Test void forumSubscriberChangesUseCurrentMembershipAndModeratorAuthority() throws Exception {
        String forum = forum();
        error(write("forumAddSubscriber", id("forumId", forum) + ", " + id("partyId", fixture.outsiderId), FORUM, reader), "NOT_FOUND");
        assertEquals(3, result(write("forumAddSubscriber", id("forumId", forum) + ", " + id("partyId", fixture.outsiderId), FORUM, owner), "forumAddSubscriber").get("subscribers").size());
        result(read("forum", id("forumId", forum), FORUM, outsider), "forum");
        result(write("forumRemoveSubscriber", id("forumId", forum) + ", " + id("partyId", fixture.recipientId), FORUM, owner), "forumRemoveSubscriber");
        error(read("forum", id("forumId", forum), FORUM, reader), "NOT_FOUND");
        var changed = result(field(true, "forumUpdateSubscribers", id("forumId", forum) + ", input: $input", FORUM,
                "ForumSubscribersInput", Map.of("input", Map.of("subscribers", List.of(fixture.actorId.toString(), fixture.recipientId.toString()))), owner), "forumUpdateSubscribers");
        assertEquals(2, changed.get("subscribers").size());
        error(read("forum", id("forumId", forum), FORUM, outsider), "NOT_FOUND");
        error(write("forumLeave", id("forumId", forum), "", owner), "BAD_USER_INPUT");
        assertTrue(result(write("forumLeave", id("forumId", forum), "", reader), "forumLeave").asBoolean());
        error(read("forum", id("forumId", forum), FORUM, reader), "NOT_FOUND");
    }

    @Test void forumPostRollsBackWhenNotificationPublishingIsDenied() throws Exception {
        String forum = forum();
        error(write("forumPost", id("forumId", forum) + ", input: {text: \"Must roll back\"}", POST, reader), "FORBIDDEN");
        assertEquals(0, result(read("forumPosts", id("forumId", forum), "{ items { id } }", owner), "forumPosts").get("items").size());
    }

    @Test void singleSubscriberPostsNeedNoNotificationPublishingGrant() throws Exception {
        String forum = result(field(true, "forumCreate", "input: $input", FORUM, "ForumCreateInput",
                Map.of("input", Map.of("realm", "WORK", "ownerId", fixture.enterpriseId.toString(), "title", "Single member")), outsider), "forumCreate").get("id").asString();
        assertEquals("Private note", result(write("forumPost", id("forumId", forum) + ", input: {text: \"Private note\"}", POST, outsider), "forumPost").get("text").asString());
    }

    @Test void conversationsShareMessagesOnlyWithCurrentParticipants() throws Exception {
        String conversation = conversation();
        String args = id("conversationId", conversation);
        result(read("conversation", args, CONVERSATION, reader), "conversation");
        var message = result(write("conversationSend", args + ", input: {text: \"Private 🌍\"}", MESSAGE, owner), "conversationSend");
        assertEquals(fixture.actorId.toString(), message.get("senderId").asString());
        var page = result(read("conversationMessages", args, "{ offset limit hasMore items " + MESSAGE + " }", reader), "conversationMessages");
        assertEquals(50, page.get("limit").asInt()); assertEquals("Private 🌍", page.get("items").get(0).get("text").asString());
        assertTrue(result(read("conversations", "", "{ items { id } }", reader), "conversations").get("items").size() > 0);
        error(read("conversationMessages", args, "{ items { id } }", outsider), "NOT_FOUND");
        error(write("conversationSend", args + ", input: {text: \"Intrusion\"}", MESSAGE, outsider), "NOT_FOUND");
        assertTrue(result(write("conversationLeave", args, "", reader), "conversationLeave").asBoolean());
        error(read("conversation", args, CONVERSATION, reader), "NOT_FOUND");
    }

    @Test void notificationsPreserveRecipientStatesAndOptionalBodyContract() throws Exception {
        String notification = publish("graphql.states"), args = id("notificationId", notification);
        var notice = result(read("notification", args, NOTICE, reader), "notification");
        assertEquals("INFO", notice.get("severity").asString()); assertEquals("Full body", notice.get("body").asString());
        assertEquals("UNREAD", notice.get("state").asString()); assertTrue(notice.get("data").isNull());
        error(read("notification", args, NOTICE, outsider), "NOT_FOUND");
        var counts = result(read("notificationCounts", "", "{ unread total capped }", reader), "notificationCounts");
        assertTrue(counts.get("unread").asLong() > 0); assertFalse(counts.get("capped").asBoolean());
        for (String operation : List.of("notificationRead", "notificationRead"))
            assertEquals("READ", result(write(operation, args, NOTICE, reader), operation).get("state").asString());
        assertEquals("ACKNOWLEDGED", result(write("notificationAcknowledge", args, NOTICE, reader), "notificationAcknowledge").get("state").asString());
        assertEquals("DISMISSED", result(write("notificationDismiss", args, NOTICE, reader), "notificationDismiss").get("state").asString());
        assertEquals(0, result(read("notifications", "category: \"graphql.states\"", "{ items { id } }", reader), "notifications").get("items").size());
        assertEquals(1, result(read("notifications", "category: \"graphql.states\", state: DISMISSED", "{ items { id state } }", reader), "notifications").get("items").size());
    }

    @Test void notificationBulkReadAndAuditKeepTheirGrantBoundaries() throws Exception {
        String notice = publish("graphql.bulk"); publish("graphql.bulk");
        assertEquals(2L, result(write("notificationReadAll", "category: \"graphql.bulk\"", "", reader), "notificationReadAll").asLong());
        assertEquals(0L, result(write("notificationReadAll", "category: \"graphql.bulk\"", "", reader), "notificationReadAll").asLong());
        error(read("notificationDeliveries", id("notificationId", notice), "{ items { id } }", reader), "FORBIDDEN");
        var page = result(read("notificationDeliveries", id("notificationId", notice), "{ offset limit hasMore items { id notification recipientId channel result detail attemptedAt } }", owner), "notificationDeliveries");
        assertEquals(50, page.get("limit").asInt());
        error(field(true, "notificationPublish", "input: $input", "{ id }", "NotificationPublishInput",
                Map.of("input", Map.of("category", "denied", "subject", "Denied", "body", "Denied", "recipients", List.of(fixture.actorId.toString()))), outsider), "FORBIDDEN");
    }

    @Test void validatesInputsAndNeverTreatsGraphqlVariablesAsIdentity() throws Exception {
        for (String field : List.of("forums", "conversations", "notifications")) {
            error(read(field, "", "{ items { id } }", null), "FORBIDDEN");
            error(read(field, "", "{ items { id } }", UUID.randomUUID().toString()), "FORBIDDEN");
            error(read(field, "limit: 101", "{ items { id } }", owner), "BAD_USER_INPUT");
            error(read(field, "offset: -1", "{ items { id } }", owner), "BAD_USER_INPUT");
        }
        error(read("forum", "forumId: \"invalid-uuid\"", FORUM, owner), "BAD_USER_INPUT");
        error(read("conversation", "conversationId: \"invalid-uuid\"", CONVERSATION, owner), "BAD_USER_INPUT");
        error(read("notification", "notificationId: \"invalid-uuid\"", NOTICE, owner), "BAD_USER_INPUT");
        for (String field : List.of("forumCreate", "conversationCreate")) {
            Map<String, Object> input = field.equals("forumCreate") ? Map.of("realm", "WORK", "ownerId", fixture.actorId.toString(), "title", "Wrong owner")
                    : Map.of("realm", "WORK", "ownerId", fixture.actorId.toString());
            error(field(true, field, "input: $input", "{ id }", field.equals("forumCreate") ? "ForumCreateInput" : "ConversationCreateInput",
                    Map.of("input", input), owner), "FORBIDDEN");
        }
        String mismatch = CommunicationsTestHost.issue(identity(fixture.actorId, UUID.randomUUID(), fixture.token));
        String badToken = CommunicationsTestHost.issue(identity(fixture.actorId, fixture.enterpriseId, UUID.randomUUID()));
        for (String field : List.of("forums", "conversations", "notifications")) {
            error(read(field, "", "{ items { id } }", mismatch), "FORBIDDEN");
            error(read(field, "", "{ items { id } }", badToken), "FORBIDDEN");
        }
        String convo = conversation();
        error(field(true, "conversationSend", id("conversationId", convo) + ", input: $input", MESSAGE, "ConversationSendInput",
                Map.of("input", Map.of("text", "bad\u0000text")), owner), "BAD_USER_INPUT");
        var unknown = execute("{ forums(enterprise: \"CommunicationsTest\", actorId: \"" + fixture.actorId + "\") { items { id } } }", Map.of(), owner);
        assertTrue(unknown.has("errors"));
    }

    @Test void concurrentRequestsKeepTheirOwnVerifiedIdentity() throws Exception {
        String forum = forum(), convo = conversation(), notice = publish("graphql.concurrent");
        String query = "{ forum(enterprise: \"CommunicationsTest\", " + id("forumId", forum) + ") { id } "
                + "conversation(enterprise: \"CommunicationsTest\", " + id("conversationId", convo) + ") { id } "
                + "notification(enterprise: \"CommunicationsTest\", " + id("notificationId", notice) + ") { id } }";
        List<CompletableFuture<Void>> calls = java.util.stream.IntStream.range(0, 12).mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
                var response = execute(query, Map.of(), i % 2 == 0 ? reader : outsider);
                if (i % 2 == 0) {
                    result(response, "forum"); result(response, "conversation"); result(response, "notification");
                } else error(response, "NOT_FOUND");
            } catch (Exception failure) { throw new RuntimeException(failure); }
        })).toList();
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(45, TimeUnit.SECONDS);
        error(read("forums", "", "{ items { id } }", null), "FORBIDDEN");
    }
}
