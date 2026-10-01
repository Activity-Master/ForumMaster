package com.guicedee.activitymaster.forums.test;

import com.guicedee.activitymaster.conversations.ConversationApi;
import com.guicedee.activitymaster.conversations.ConversationIdentityProvider;
import com.guicedee.activitymaster.conversations.graphql.ConversationGraphQLSchemaProvider;
import com.guicedee.activitymaster.forums.ForumApi;
import com.guicedee.activitymaster.forums.ForumIdentityProvider;
import com.guicedee.activitymaster.forums.graphql.ForumGraphQLSchemaProvider;
import com.guicedee.activitymaster.fsdm.graphql.ActivityMasterGraphQLAdapter;
import com.guicedee.activitymaster.notifications.NotificationApi;
import com.guicedee.activitymaster.notifications.NotificationIdentityProvider;
import com.guicedee.activitymaster.notifications.NotificationModels.Counts;
import com.guicedee.activitymaster.notifications.graphql.NotificationGraphQLSchemaProvider;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class CommunicationsGraphQLContractTest {
    private GraphQL schema(RuntimeWiring.Builder wiring, TypeDefinitionRegistry registry) {
        return GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(registry, wiring.build())).build();
    }
    @Test void composesStandaloneModulesAndDeniesMissingHostIdentityBeforePersistence() {
        var registry = new SchemaParser().parse("type Query { _health: Boolean } type Mutation { _health: Boolean }");
        var wiring = RuntimeWiring.newRuntimeWiring();
        var forums = new ForumGraphQLSchemaProvider(new ForumApi(new ForumIdentityProvider.Deny(), null, null, null, new NotificationIdentityProvider.Deny()));
        var conversations = new ConversationGraphQLSchemaProvider(new ConversationApi(new ConversationIdentityProvider.Deny(), null));
        var notifications = new NotificationGraphQLSchemaProvider(new NotificationApi(new NotificationIdentityProvider.Deny(), null, null));
        registry.merge(forums.getTypeDefinitions()); forums.configureWiring(wiring);
        registry.merge(conversations.getTypeDefinitions()); conversations.configureWiring(wiring);
        registry.merge(notifications.getTypeDefinitions()); notifications.configureWiring(wiring);
        var graphql = schema(wiring, registry);
        for (String field : new String[]{"forums", "conversations", "notifications"}) {
            var result = graphql.execute("{ " + field + "(enterprise: \"Any\") { items { id } } }");
            assertEquals("FORBIDDEN", result.getErrors().getFirst().getExtensions().get("code"));
        }
    }

    @Test void preservesSigned64BitNotificationCountsAndBulkResults() {
        var registry = new SchemaParser().parse("type Query { _health: Boolean } type Mutation { _health: Boolean }");
        var provider = new NotificationGraphQLSchemaProvider();
        registry.merge(provider.getTypeDefinitions());
        var scalar = provider.configureWiring(RuntimeWiring.newRuntimeWiring()).build().getScalars().get("NotificationLong");
        var wiring = RuntimeWiring.newRuntimeWiring().scalar(scalar);
        wiring.type("Query", q -> q.dataFetcher("notificationCounts", e -> new Counts(3_000_000_000L, Long.MAX_VALUE, true)));
        wiring.type("Mutation", m -> m.dataFetcher("notificationReadAll", e -> Long.MAX_VALUE));
        var graphql = schema(wiring, registry);
        var counts = graphql.execute("{ notificationCounts(enterprise: \"Any\") { unread total capped } }");
        assertTrue(counts.getErrors().isEmpty(), counts.getErrors().toString());
        Map<String, Object> data = counts.getData();
        var values = (Map<?, ?>) data.get("notificationCounts");
        assertEquals(3_000_000_000L, values.get("unread")); assertEquals(Long.MAX_VALUE, values.get("total"));
        var bulk = graphql.execute("mutation { notificationReadAll(enterprise: \"Any\") }");
        assertTrue(bulk.getErrors().isEmpty());
        assertEquals(Long.MAX_VALUE, ((Map<?, ?>) bulk.getData()).get("notificationReadAll"));
    }

    @Test void sanitizesAsyncAndSynchronousFailuresWithoutLeakingDatabaseDetails() {
        String sensitive = "SQL connection-password=never-return-this";
        Throwable[] failures = {new SecurityException(sensitive), new NotFoundException(sensitive),
                new BadRequestException(sensitive), new IllegalArgumentException(sensitive), new IllegalStateException(sensitive)};
        String[] codes = {"FORBIDDEN", "NOT_FOUND", "BAD_USER_INPUT", "BAD_USER_INPUT", "INTERNAL_SERVER_ERROR"};
        for (int i = 0; i < failures.length; i++) {
            final Throwable failure = failures[i];
            var wiring = RuntimeWiring.newRuntimeWiring().type("Query", q -> q.dataFetcher("probe",
                    ActivityMasterGraphQLAdapter.fetch("Test", e -> Uni.createFrom().failure(new CompletionException(failure)))));
            var result = schema(wiring, new SchemaParser().parse("type Query { probe: String }")).execute("{ probe }");
            assertEquals(codes[i], result.getErrors().getFirst().getExtensions().get("code"));
            assertFalse(result.toSpecification().toString().contains(sensitive));
        }
        var wiring = RuntimeWiring.newRuntimeWiring().type("Query", q -> q.dataFetcher("probe",
                ActivityMasterGraphQLAdapter.fetch("Test", e -> { throw new IllegalStateException(sensitive); })));
        var result = schema(wiring, new SchemaParser().parse("type Query { probe: String }")).execute("{ probe }");
        assertEquals("INTERNAL_SERVER_ERROR", result.getErrors().getFirst().getExtensions().get("code"));
        assertFalse(result.toSpecification().toString().contains(sensitive));
    }
}
