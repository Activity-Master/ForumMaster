package com.guicedee.activitymaster.forums.graphql;

import com.guicedee.activitymaster.forums.ForumApi;
import com.guicedee.activitymaster.forums.ForumModels.*;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.Map;

import static com.guicedee.activitymaster.fsdm.graphql.ActivityMasterGraphQLAdapter.*;

/** Forum GraphQL transport; ForumApi owns authority, persistence and post notifications. */
public final class ForumGraphQLSchemaProvider implements IGraphQLSchemaProvider<ForumGraphQLSchemaProvider> {
    private final ForumApi suppliedApi;
    public ForumGraphQLSchemaProvider() { suppliedApi = null; }
    public ForumGraphQLSchemaProvider(ForumApi api) { suppliedApi = api; }
    private ForumApi api() { return suppliedApi == null ? IGuiceContext.get(ForumApi.class) : suppliedApi; }

    private static final String SDL = """
        enum ForumRealm { PERSONAL SOCIAL WORK }
        type Forum { id: ID!, realm: ForumRealm!, ownerId: ID!, title: String!, subscribers: [ID!]! }
        type ForumPost { id: ID!, forumId: ID!, posterId: ID!, text: String!, createdAt: String! }
        type ForumPage { items: [Forum!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        type ForumPostPage { items: [ForumPost!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        input ForumCreateInput { realm: ForumRealm!, ownerId: ID!, title: String!, subscribers: [ID!]! = [] }
        input ForumPostInput { text: String! }
        input ForumSubscribersInput { subscribers: [ID!]! }
        extend type Query {
            forum(enterprise: String!, forumId: ID!): Forum!
            forums(enterprise: String!, offset: Int! = 0, limit: Int! = 50): ForumPage!
            forumPosts(enterprise: String!, forumId: ID!, offset: Int! = 0, limit: Int! = 50): ForumPostPage!
        }
        extend type Mutation {
            forumCreate(enterprise: String!, input: ForumCreateInput!): Forum!
            forumPost(enterprise: String!, forumId: ID!, input: ForumPostInput!): ForumPost!
            forumAddSubscriber(enterprise: String!, forumId: ID!, partyId: ID!): Forum!
            forumRemoveSubscriber(enterprise: String!, forumId: ID!, partyId: ID!): Forum!
            forumUpdateSubscribers(enterprise: String!, forumId: ID!, input: ForumSubscribersInput!): Forum!
            forumLeave(enterprise: String!, forumId: ID!): Boolean!
        }
        """;

    @Override public TypeDefinitionRegistry getTypeDefinitions() { return new SchemaParser().parse(SDL); }
    @Override public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder) {
        return builder.type("Query", q -> q
                .dataFetcher("forum", fetch("Forum", e -> api().find(e.getArgument("enterprise"), id(e, "forumId"))))
                .dataFetcher("forums", fetch("Forum", e -> api().list(e.getArgument("enterprise"), e.getArgument("offset"), e.getArgument("limit"))))
                .dataFetcher("forumPosts", fetch("Forum", e -> api().posts(e.getArgument("enterprise"), id(e, "forumId"), e.getArgument("offset"), e.getArgument("limit")))))
            .type("Mutation", m -> m
                .dataFetcher("forumCreate", fetch("Forum", e -> {
                    Map<String, Object> input = e.getArgument("input");
                    return api().create(e.getArgument("enterprise"), new Create(ActivityScope.Realm.valueOf((String) input.get("realm")),
                            java.util.UUID.fromString((String) input.get("ownerId")), (String) input.get("title"), ids(strings(input, "subscribers"))));
                }))
                .dataFetcher("forumPost", fetch("Forum", e -> {
                    Map<String, Object> input = e.getArgument("input");
                    return api().post(e.getArgument("enterprise"), id(e, "forumId"), new PublishPost((String) input.get("text")));
                }))
                .dataFetcher("forumAddSubscriber", fetch("Forum", e -> api().addSubscriber(e.getArgument("enterprise"), id(e, "forumId"), id(e, "partyId"))))
                .dataFetcher("forumRemoveSubscriber", fetch("Forum", e -> api().removeSubscriber(e.getArgument("enterprise"), id(e, "forumId"), id(e, "partyId"))))
                .dataFetcher("forumUpdateSubscribers", fetch("Forum", e -> {
                    Map<String, Object> input = e.getArgument("input");
                    return api().updateSubscribers(e.getArgument("enterprise"), id(e, "forumId"), new UpdateSubscribers(ids(strings(input, "subscribers"))));
                }))
                .dataFetcher("forumLeave", fetch("Forum", e -> api().leave(e.getArgument("enterprise"), id(e, "forumId")).replaceWith(true))));
    }
    @SuppressWarnings("unchecked")
    private static java.util.List<String> strings(Map<String, Object> input, String key) { return (java.util.List<String>) input.get(key); }
}
